package com.aiciv.bot;

import com.aiciv.AiCiv;
import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.SayAction;
import com.aiciv.actions.ToolCatalog;
import com.aiciv.brain.PromptBuilder;
import com.aiciv.brain.ThinkQueue;
import com.aiciv.brain.WakeReason;
import com.aiciv.llm.LlmService;
import com.aiciv.llm.ToolCall;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotFormatter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** Wakes bots, gives queued bots their LLM turn when a provider is free, and applies the replies. */
final class BrainScheduler {
    private final BotManager m;

    BrainScheduler(BotManager m) {
        this.m = m;
    }

    void tick(long now) {
        for (Bot b : m.bots()) {
            if (b.player.isDeadOrDying()) continue;
            WakeReason r = b.brain.pollWake(now, b.executor.busy());
            if (r != null) m.queue().offer(b.name, r, now);
        }
        while (true) {
            Optional<ThinkQueue.Ticket> ticket = m.queue().pollFirst(name -> m.bot(name)
                    .map(b -> m.llm().peek(b.brain.modelKey()).isPresent())
                    .orElse(true));
            if (ticket.isEmpty()) return;
            Optional<Bot> bot = m.bot(ticket.get().bot());
            if (bot.isEmpty() || bot.get().player.isDeadOrDying()) continue;
            if (!think(bot.get(), ticket.get(), now)) return;
        }
    }

    private boolean think(Bot b, ThinkQueue.Ticket ticket, long now) {
        String snapshot = SnapshotFormatter.format(
                Perceiver.capture(b.player, b.executor.currentDescription(), b.brain.lastDecision(), p -> p instanceof AiPlayer),
                SnapshotFormatter.MAX_CHARS);
        String system = PromptBuilder.system(b.name, b.brain.persona(), b.brain.goal(), m.othersFor(b),
                m.config().maxActionsPerDecision, ToolCatalog.guide());
        String user = PromptBuilder.user(List.of(), b.brain.events().lines(), snapshot);
        Optional<CompletableFuture<LlmService.Outcome>> sent = m.llm().trySend(b.brain.modelKey(), system, user, ToolCatalog.tools());
        if (sent.isEmpty()) {
            m.queue().offer(b.name, ticket.reason(), ticket.since());
            return false;
        }
        b.brain.dispatched(now);
        int snapshotChars = snapshot.length();
        sent.get().whenComplete((outcome, err) -> m.server().execute(() -> apply(b, ticket, snapshotChars, outcome, err)));
        return true;
    }

    private void apply(Bot b, ThinkQueue.Ticket ticket, int snapshotChars, LlmService.Outcome outcome, Throwable err) {
        if (m.bot(b.name).orElse(null) != b) return; // removed or replaced while thinking
        try {
            applyChecked(b, ticket, snapshotChars, outcome, err);
        } catch (RuntimeException e) {
            AiCiv.LOG.warn("AiCiv could not apply the reply for {}", b.name, e);
            JsonObject log = new JsonObject();
            log.addProperty("time", Instant.ofEpochMilli(m.clock().millis()).toString());
            log.addProperty("trigger", ticket.reason().name());
            log.addProperty("error", "could not apply reply: " + e);
            m.decisionLog().append(b.name, log);
            b.brain.failed(ticket.reason());
        }
    }

    private void applyChecked(Bot b, ThinkQueue.Ticket ticket, int snapshotChars, LlmService.Outcome outcome, Throwable err) {
        JsonObject log = new JsonObject();
        log.addProperty("time", Instant.ofEpochMilli(m.clock().millis()).toString());
        log.addProperty("trigger", ticket.reason().name());
        log.addProperty("snapshotChars", snapshotChars);
        if (outcome != null) log.addProperty("provider", outcome.route().providerKey());

        if (b.brain.paused()) {
            log.addProperty("discarded", "paused");
            b.brain.replied("paused");
            m.decisionLog().append(b.name, log);
            return;
        }

        if (err != null || !(outcome instanceof LlmService.Outcome.Reply reply)) {
            String why = err != null ? String.valueOf(err)
                    : outcome instanceof LlmService.Outcome.Failed f ? f.reason() : "rate limited";
            if (outcome instanceof LlmService.Outcome.Failed failed && m.llm().providerDisabled(failed.route().providerKey())) {
                m.warnOpsOnce(failed.route().providerKey(), failed.reason());
            }
            log.addProperty("error", why);
            b.brain.failed(ticket.reason());
            m.decisionLog().append(b.name, log);
            return;
        }

        log.addProperty("model", reply.route().modelId());
        log.addProperty("thought", reply.reply().thought());
        int max = m.config().maxActionsPerDecision;
        List<Action> actions = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        JsonArray calls = new JsonArray();
        for (ToolCall c : reply.reply().calls()) {
            JsonObject jc = new JsonObject();
            jc.addProperty("name", c.name());
            jc.add("args", c.args());
            calls.add(jc);
            if (actions.size() >= max) {
                problems.add("only " + max + " actions per turn; ignored " + c.name());
                continue;
            }
            switch (ActionFactory.create(c)) {
                case ActionFactory.Result.Ok ok -> actions.add(ok.action());
                case ActionFactory.Result.Invalid bad -> problems.add(bad.reason());
            }
        }
        if (actions.isEmpty() && reply.reply().plainText() != null) actions.add(new SayAction(reply.reply().plainText()));
        log.add("calls", calls);
        if (reply.reply().plainText() != null) log.addProperty("text", reply.reply().plainText());
        JsonArray problemsJson = new JsonArray();
        problems.forEach(problemsJson::add);
        log.add("problems", problemsJson);

        String stamp = m.stamp();
        if (!problems.isEmpty()) b.brain.note(stamp, "problems with your last turn: " + String.join("; ", problems));
        if (actions.isEmpty()) {
            b.brain.note(stamp, "your last turn did nothing");
        } else if (actions.stream().allMatch(a -> a instanceof SayAction)) {
            // Talking does not interrupt what the bot is doing.
            for (Action a : actions) a.tick(b.context);
        } else {
            b.executor.replace(actions, b.context);
        }
        b.brain.replied(actions.isEmpty() ? "nothing" : actions.stream().map(Action::describe).collect(Collectors.joining(", ")));
        m.decisionLog().append(b.name, log);
    }
}
