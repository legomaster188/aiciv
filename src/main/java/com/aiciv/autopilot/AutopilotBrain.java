package com.aiciv.autopilot;

import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.GoalLibrary;
import com.aiciv.autopilot.personality.Personality;
import com.aiciv.llm.ToolCall;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One autopilot bot's decision state: spec, personality, engine, chat and memory. Server thread only. */
public final class AutopilotBrain {
    private final String botName;
    private AutopilotSpec spec;
    private Personality personality;
    private AutopilotEngine engine;
    private AutopilotChat chat;
    private Tuning tuning;
    private final AutopilotMemory memory = new AutopilotMemory();
    private final PulseWatch pulses = new PulseWatch();
    /** The calls of the step in flight, so a decision repeating them while busy keeps the running action. */
    private List<ToolCall> inFlightCalls = List.of();
    private String lastThought = "nothing yet";
    private Situation lastSituation;
    private long lastTick;
    /** When the world was last read for a decision, and the re-stamped reading handed out by {@link #reusable}. */
    private long capturedTick = Long.MIN_VALUE / 2;
    private Situation reused;

    /** How long a world reading may serve a busy bot's later decisions. */
    public static final int REUSE_TICKS = 40;

    public AutopilotBrain(AutopilotSpec spec, Personality p, AutopilotEngine engine, AutopilotChat chat) {
        this(null, spec, p, engine, chat);
    }

    /** {@code botName} lets requests addressed to the bot be recognised before its first decision. */
    public AutopilotBrain(String botName, AutopilotSpec spec, Personality p, AutopilotEngine engine, AutopilotChat chat) {
        this.botName = botName;
        respec(spec, p, engine, chat);
    }

    public AutopilotSpec spec() { return spec; }
    public Personality personality() { return personality; }
    public AutopilotMemory memory() { return memory; }
    public AutopilotChat chat() { return chat; }
    public Tuning tuning() { return tuning; }
    public Situation lastSituation() { return lastSituation; }

    public Decision decide(Situation s, String trigger, long tick, long nowMillis) {
        lastTick = tick;
        lastSituation = s;
        if (s != reused) capturedTick = tick;
        reused = null;
        List<String> reached = Milestones.reached(s);
        if (memory.decisions() == 0) {
            memory.bragged().addAll(reached); // what the bot had when it joined is not news
        } else {
            for (String m : reached) if (memory.bragged().add(m)) chat.observe(new AutopilotEvent.Milestone(m), nowMillis);
        }
        memory.decided();

        Decision d = engine.decide(s, memory, personality, trigger, tick);
        if (d.goal() != null && !d.goal().equals(memory.goal())) {
            String summary = GoalLibrary.byId(d.goal()).map(Goal::summary).orElse(d.goal());
            chat.observe(new AutopilotEvent.GoalChanged(d.goal(), summary), nowMillis);
        }
        acknowledgeRequests(d, tick, nowMillis);
        Optional<AutopilotMemory.InFlight> running = memory.inFlight();
        if (s.busy() && !"ACTION_ENDED".equals(trigger) && running.isPresent()
                && Objects.equals(running.get().goal(), d.goal()) && Objects.equals(running.get().step(), d.step())
                && sameCalls(inFlightCalls, d.calls())) {
            return d.kept();
        }
        if (d.goal() != null) memory.current(d.goal(), d.step());
        if (!d.calls().isEmpty()) {
            memory.stepStarted(d.goal(), d.step(), d.flag(), tick);
            inFlightCalls = d.calls();
        }
        if (d.step() != null && d.step().startsWith("request_")) {
            List<AutopilotMemory.Request> pending = memory.requests(tick);
            if (!pending.isEmpty()) {
                // a refusal has nothing to wait for; a give, come or follow counts once its action finished
                if (d.calls().isEmpty()) memory.handled(pending.get(0));
                else memory.answering(pending.get(0));
            }
        }
        for (ChatCue cue : d.cues()) chat.cue(cue, nowMillis);
        lastThought = d.thought();
        return d;
    }

    /**
     * For a busy bot woken by a mention or by damage, the last world reading when it is under {@link #REUSE_TICKS} old,
     * re-stamped with this tick, health, food and the memory: such a decision usually keeps the running action, and a
     * full snapshot for it would be wasted. An urgent pulse, an ended action or an idle bot always gets a fresh reading.
     */
    public Optional<Situation> reusable(String trigger, boolean busy, String currentAction, int health, int food,
                                        String pinnedGoal, long tick) {
        Situation last = lastSituation;
        if (!busy || last == null || tick - capturedTick >= REUSE_TICKS) return Optional.empty();
        if (!"MENTIONED".equals(trigger) && !"HURT".equals(trigger)) return Optional.empty();
        reused = new Situation(last.name(), last.dimension(), last.x(), last.y(), last.z(), health, food, last.night(), tick,
                last.inventory(), last.worn(), last.holding(), last.freeSlots(), last.blocks(), last.players(), last.mobs(),
                last.hostiles(), last.remembered(), last.tags(), last.structures(), last.actions(), currentAction, true,
                Set.copyOf(memory.flags()), new HashMap<>(memory.lastDone()), memory.requests(tick), tuning, pinnedGoal);
        return Optional.of(reused);
    }

    /**
     * A person whose request this decision does not answer hears "busy, ask later" once, or a refusal from a bot that
     * never helps (that request is then dropped), instead of silence.
     */
    private void acknowledgeRequests(Decision d, long tick, long nowMillis) {
        if ("social".equals(d.goal())) return;
        for (AutopilotMemory.Request r : memory.requests(tick)) {
            if (!r.human() || !memory.acknowledge(r)) continue;
            Map<String, String> vars = new HashMap<>();
            vars.put("speaker", r.from());
            if (r.item() != null && !r.item().isBlank()) vars.put("item", r.item().replace('_', ' '));
            if (tuning.helpsWhenAsked()) {
                chat.cue(new ChatCue("busy", vars, true), nowMillis);
            } else {
                chat.cue(new ChatCue("refuse", vars, true), nowMillis);
                memory.handled(r);
            }
        }
    }

    private static boolean sameCalls(List<ToolCall> a, List<ToolCall> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name().equals(b.get(i).name()) || !Objects.equals(a.get(i).args(), b.get(i).args())) return false;
        }
        return true;
    }

    public void actionsEnded(String summary, long tick, long nowMillis) {
        lastTick = tick;
        memory.outcome(summary, tick, tuning.maxRetries());
        chat.observe(new AutopilotEvent.ActionsEnded(summary), nowMillis);
    }

    /** The urgent reason for this pulse, or null; nightfall is also told to the chat. */
    public String urgent(Pulse pulse, long nowMillis) {
        lastTick = pulse.tick();
        String reason = pulses.check(pulse, tuning);
        if ("night fell".equals(reason)) chat.observe(new AutopilotEvent.NightFell(), nowMillis);
        return reason;
    }

    /** As {@link #observe(AutopilotEvent, long, long)} at the last tick this brain saw. */
    public void observe(AutopilotEvent e, long nowMillis) {
        observe(e, lastTick, nowMillis);
    }

    /** Heard lines may be requests, which go to memory; every event goes to the chat. */
    public void observe(AutopilotEvent e, long tick, long nowMillis) {
        lastTick = Math.max(lastTick, tick);
        // an autopilot's open "anyone got…?" is one of its chat templates, not something to answer
        if (e instanceof AutopilotEvent.Heard h && !(h.speakerIsAutopilot() && !h.mentioned())) {
            String me = botName != null ? botName : lastSituation != null ? lastSituation.name() : "";
            Requests.parse(h.text(), me, h.mentioned(), tick)
                    .map(r -> new AutopilotMemory.Request(r.kind(), h.speaker(), r.item(), r.count(), r.tick(), !h.speakerIsAi()))
                    .ifPresent(memory::heard);
        }
        chat.observe(e, nowMillis);
    }

    /** Switches spec, personality, engine and chat; memory and the chat's cooldowns are kept. */
    public void respec(AutopilotSpec spec, Personality p, AutopilotEngine engine, AutopilotChat chat) {
        if (this.chat != null && chat != this.chat) chat.carryOver(this.chat);
        this.spec = spec;
        this.personality = p;
        this.engine = engine;
        this.chat = chat;
        this.tuning = engine.tuning(p);
    }

    public AutopilotView view() {
        return new AutopilotView(personality.id(), personality.name(), memory.goal(), memory.step(), lastThought,
                personality.traits().asMap(), spec.chatModel());
    }
}
