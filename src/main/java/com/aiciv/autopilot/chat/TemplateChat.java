package com.aiciv.autopilot.chat;

import com.aiciv.autopilot.AutopilotChat;
import com.aiciv.autopilot.AutopilotEvent;
import com.aiciv.autopilot.AutopilotSpec;
import com.aiciv.autopilot.ChatCue;
import com.aiciv.autopilot.ChatPort;
import com.aiciv.autopilot.Requests;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import com.aiciv.autopilot.personality.Personality;
import com.aiciv.config.AiCivConfig;
import com.aiciv.llm.LlmReply;
import com.aiciv.llm.LlmService;
import com.aiciv.perception.Sighting;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;

/**
 * An autopilot's chat: events become cues, cues become lines from the personality's phrase book, and an optional
 * cheap model may rewrite the line in its own words. Server thread only (the model's reply is handed back through
 * {@link ChatPort#onServer}).
 */
public final class TemplateChat implements AutopilotChat {
    private static final int MAX_PENDING = 8;
    private static final int REMEMBERED = 3;
    private static final int BANTER_RANGE = 16;
    private static final long AI_MENTION_GAP_MILLIS = 60_000;
    private static final String AI_TAG = " [AI]";

    private record Pending(String category, Map<String, String> vars, ChatPolicy.Kind kind, long at, String pair,
                           String requestText) {}

    /** A model request in flight; the template is what gets said when the model gives nothing usable. */
    private static final class Llm {
        final Pending cue;
        final String template;
        Optional<String> result; // set on the server thread once the model answered; empty text means no usable line

        Llm(Pending cue, String template) {
            this.cue = cue;
            this.template = template;
        }
    }

    private final AutopilotSpec spec;
    private final Personality personality;
    private final AiCivConfig.AutopilotConfig cfg;
    private final PhraseBook book;
    private final RandomGenerator rng;
    private final ChatPolicy policy;
    private final List<Pending> pending = new ArrayList<>();
    private final List<String> heard = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private final Map<String, Long> lastAiMention = new HashMap<>();
    private Llm llm;

    public TemplateChat(AutopilotSpec spec, Personality personality, Tuning tuning, AiCivConfig.AutopilotConfig cfg,
                        PhraseBook book, RandomGenerator rng) {
        this.spec = spec;
        this.personality = personality;
        this.cfg = cfg;
        this.book = book;
        this.rng = rng;
        this.policy = new ChatPolicy(personality.traits().social(), tuning, cfg);
    }

    @Override
    public void observe(AutopilotEvent event, long nowMillis) {
        switch (event) {
            case AutopilotEvent.Spawned s -> enqueue("greet", Map.of(), ChatPolicy.Kind.OPTIONAL, nowMillis, null, null);
            case AutopilotEvent.PlayerJoined j -> enqueue("greet", vars("other", j.name()), ChatPolicy.Kind.OPTIONAL, nowMillis, null, null);
            case AutopilotEvent.Milestone m -> enqueue("brag", vars("item", m.what() == null ? null : m.what().replace('_', ' ')), ChatPolicy.Kind.PRIORITY, nowMillis, null, null);
            case AutopilotEvent.Hurt h -> {
                if (h.health() <= 10) enqueue("complain", vars("attacker", h.attacker()), ChatPolicy.Kind.OPTIONAL, nowMillis, null, null);
            }
            case AutopilotEvent.Died d -> enqueue("died", vars("message", d.message()), ChatPolicy.Kind.PRIORITY, nowMillis, null, null);
            case AutopilotEvent.OtherDied o -> enqueue("other_died", vars("dead", o.name(), "message", o.message()), ChatPolicy.Kind.OPTIONAL, nowMillis, null, null);
            case AutopilotEvent.Heard h -> heard(h, nowMillis);
            case AutopilotEvent.NightFell n -> enqueue("night", Map.of(), ChatPolicy.Kind.OPTIONAL, nowMillis, null, null);
            case AutopilotEvent.GoalChanged g -> enqueue("goal", vars("goal", g.summary()), ChatPolicy.Kind.OPTIONAL, nowMillis, null, null);
            case AutopilotEvent.Received r -> enqueue("thanks", vars("speaker", r.from()), ChatPolicy.Kind.REPLY, nowMillis, null, null);
            case AutopilotEvent.ActionsEnded a -> {}
            case AutopilotEvent.StepBlocked b -> {}
        }
    }

    private static Map<String, String> vars(String... keyValues) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) if (keyValues[i + 1] != null) m.put(keyValues[i], keyValues[i + 1]);
        return m;
    }

    private static Map<String, String> withoutNulls(Map<String, String> in) {
        Map<String, String> m = new HashMap<>();
        in.forEach((k, v) -> {
            if (v != null) m.put(k, v);
        });
        return m;
    }

    private void heard(AutopilotEvent.Heard h, long now) {
        heard.add(h.speaker() + ": " + h.text());
        while (heard.size() > REMEMBERED) heard.remove(0);
        if (!h.mentioned()) return;
        if (h.speakerIsAi()) {
            // another autopilot name-dropping us would otherwise start an endless two-bot conversation
            Long last = lastAiMention.get(h.speaker());
            if (last != null && now - last < AI_MENTION_GAP_MILLIS) return;
            lastAiMention.put(h.speaker(), now);
        }
        enqueue("mention", vars("speaker", h.speaker(), "text", h.text()), ChatPolicy.Kind.REPLY, now, null, h.text());
    }

    @Override
    public void cue(ChatCue cue, long nowMillis) {
        if (!PhraseBook.CATEGORIES.containsKey(cue.category())) return;
        enqueue(cue.category(), cue.vars(), ChatPolicy.kind(cue.category(), cue.priority()), nowMillis, null, null);
    }

    private void enqueue(String category, Map<String, String> vars, ChatPolicy.Kind kind, long now, String pair, String requestText) {
        if (!policy.categoryAllowed(category)) return;
        if (kind == ChatPolicy.Kind.OPTIONAL && !policy.optionalSpeaks(rng)) return;
        pending.add(new Pending(category, Map.copyOf(withoutNulls(vars)), kind, now, pair, requestText));
        while (pending.size() > MAX_PENDING) evictOne();
    }

    /** Drops the oldest cue of the least important kind, so a flood of optional chatter never pushes out a reply. */
    private void evictOne() {
        for (ChatPolicy.Kind kind : new ChatPolicy.Kind[] {ChatPolicy.Kind.OPTIONAL, ChatPolicy.Kind.REPLY, ChatPolicy.Kind.PRIORITY}) {
            for (int i = 0; i < pending.size(); i++) {
                if (pending.get(i).kind() == kind) {
                    pending.remove(i);
                    return;
                }
            }
        }
    }

    @Override
    public void tick(ChatPort port, long now) {
        if (llm != null && !finishLlm(port, now)) return;
        pending.removeIf(p -> now - p.at() > ChatPolicy.CUE_MAX_AGE_MILLIS);
        scanForBanter(port, now);
        for (ChatPolicy.Kind wanted : new ChatPolicy.Kind[] {ChatPolicy.Kind.PRIORITY, null}) {
            for (int i = 0; i < pending.size(); i++) {
                Pending cue = pending.get(i);
                if (wanted != null ? cue.kind() != wanted : cue.kind() == ChatPolicy.Kind.PRIORITY) continue;
                if (cue.requestText() != null && Requests.parse(cue.requestText(), port.botName(), true, 0).isPresent()) {
                    pending.remove(i--); // a request: the planner answers it with give / refuse / coming
                    continue;
                }
                if (!policy.mayNowSpeak(cue.kind(), now, port)) continue;
                pending.remove(i--);
                Optional<String> line = pick(cue, port);
                if (line.isEmpty()) continue;
                speakOrAsk(port, cue, line.get(), now);
                return;
            }
        }
    }

    private void scanForBanter(ChatPort port, long now) {
        if (!policy.categoryAllowed("banter")) return;
        Situation s = port.situation();
        if (s == null) return;
        for (Sighting p : s.players()) {
            if (p.distance() > BANTER_RANGE || !p.label().endsWith(AI_TAG)) continue;
            String other = p.label().substring(0, p.label().length() - AI_TAG.length());
            String model = port.modelOf(other);
            if (model == null) continue;
            String a = port.botName().toLowerCase(Locale.ROOT);
            String b = other.toLowerCase(Locale.ROOT);
            String pair = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            if (policy.considerBanter(pair, now)) {
                enqueue("banter", vars("other", other, "otherModel", model), ChatPolicy.Kind.OPTIONAL, now, pair, null);
            }
        }
    }

    /** A line of the cue's category that renders now, avoiding what this bot just said. */
    private Optional<String> pick(Pending cue, ChatPort port) {
        Map<String, String> vars = new HashMap<>(cue.vars());
        vars.put("me", port.botName());
        vars.put("personality", personality.name());
        Set<String> rendered = new LinkedHashSet<>();
        for (String template : book.lines(cue.category())) Templates.render(template, vars).ifPresent(rendered::add);
        if (rendered.isEmpty()) return Optional.empty();
        List<String> fresh = rendered.stream().filter(l -> !said.contains(l)).toList();
        String last = said.isEmpty() ? null : said.get(said.size() - 1);
        List<String> from = !fresh.isEmpty() ? fresh : rendered.stream().filter(l -> !l.equals(last)).toList();
        if (from.isEmpty()) from = List.copyOf(rendered);
        return Optional.of(from.get(rng.nextInt(from.size())));
    }

    private void speakOrAsk(ChatPort port, Pending cue, String template, long now) {
        String model = spec.chatModel();
        if (model != null && LlmChat.CATEGORIES.contains(cue.category()) && port.llmReady(model) && policy.llmCooledDown(now)) {
            String system = LlmChat.system(port.botName(), personality);
            String user = LlmChat.user(cue.category(), cue.vars(), port.situation(), List.copyOf(heard), template);
            Optional<CompletableFuture<LlmService.Outcome>> sent = port.sendLlm(model, system, user);
            if (sent.isPresent()) {
                policy.llmRequested(now);
                Llm request = new Llm(cue, template);
                llm = request;
                String me = port.botName();
                sent.get().whenComplete((outcome, error) -> {
                    Optional<String> text = error == null ? usable(outcome, me) : Optional.empty();
                    port.onServer(() -> request.result = Optional.of(text.orElse("")));
                });
                return;
            }
        }
        say(port, cue, template, "template", now);
    }

    private static Optional<String> usable(LlmService.Outcome outcome, String me) {
        if (!(outcome instanceof LlmService.Outcome.Reply r)) return Optional.empty();
        LlmReply reply = r.reply();
        String raw = reply.plainText() != null && !reply.plainText().isBlank() ? reply.plainText() : reply.thought();
        return LlmChat.clean(raw, me);
    }

    /** Handles a model request that may have finished; true when the chat is free to pick the next cue. */
    private boolean finishLlm(ChatPort port, long now) {
        Llm request = llm;
        long age = now - request.cue.at();
        long maxAge = cfg.chatLlmMaxAgeSeconds * 1000L;
        if (age > maxAge) {
            llm = null; // too late to be about what happened: say nothing
            return true;
        }
        if (request.result == null) return false;
        if (!port.globalGapOk(now)) return false; // somebody else spoke meanwhile: wait for the gap while still fresh
        llm = null;
        String text = request.result.get();
        if (text.isEmpty()) say(port, request.cue, request.template, "template", now);
        else say(port, request.cue, text, "llm", now);
        return false;
    }

    private void say(ChatPort port, Pending cue, String line, String source, long now) {
        port.say(line);
        port.spoke(now);
        policy.spoke(cue.kind(), now);
        if (cue.pair() != null) policy.banterSpoken(cue.pair(), now);
        said.add(line);
        while (said.size() > REMEMBERED) said.remove(0);
        JsonObject log = new JsonObject();
        log.addProperty("time", Instant.ofEpochMilli(now).toString());
        log.addProperty("mode", "autopilot-chat");
        log.addProperty("event", cue.category());
        log.addProperty("source", source);
        log.addProperty("model", spec.chatModel());
        log.addProperty("text", line);
        port.log(log);
    }
}
