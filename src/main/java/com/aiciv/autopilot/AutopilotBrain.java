package com.aiciv.autopilot;

import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.GoalLibrary;
import com.aiciv.autopilot.personality.Personality;
import com.aiciv.llm.ToolCall;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
            if (!pending.isEmpty()) memory.handled(pending.get(0));
        }
        for (ChatCue cue : d.cues()) chat.cue(cue, nowMillis);
        lastThought = d.thought();
        return d;
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
        if (e instanceof AutopilotEvent.Heard h) {
            String me = botName != null ? botName : lastSituation != null ? lastSituation.name() : "";
            Requests.parse(h.text(), me, h.mentioned(), tick)
                    .map(r -> new AutopilotMemory.Request(r.kind(), h.speaker(), r.item(), r.count(), r.tick()))
                    .ifPresent(memory::heard);
        }
        chat.observe(e, nowMillis);
    }

    /** Switches spec, personality, engine and chat; memory is kept. */
    public void respec(AutopilotSpec spec, Personality p, AutopilotEngine engine, AutopilotChat chat) {
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
