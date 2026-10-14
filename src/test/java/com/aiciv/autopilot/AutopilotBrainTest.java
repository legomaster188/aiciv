package com.aiciv.autopilot;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.personality.Personality;
import com.aiciv.autopilot.personality.Traits;
import com.aiciv.llm.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutopilotBrainTest {
    private static final Personality P = new Personality("default", "Default", "An ordinary survivor.",
            new Traits(0.4, 0.5, -0.3, 0.2, 0.6), Map.of(), Map.of());
    private static final AutopilotSpec SPEC = new AutopilotSpec("autopilot-default", "default", null, 5);

    /** Returns whatever decision the test sets next. */
    private static final class FakeEngine implements AutopilotEngine {
        Decision next = Decision.waiting("nothing to do");
        int calls;

        @Override
        public Decision decide(Situation s, AutopilotMemory mem, Personality p, String trigger, long tick) {
            calls++;
            return next;
        }
    }

    private static final class RecordingChat implements AutopilotChat {
        final List<AutopilotEvent> events = new ArrayList<>();
        final List<ChatCue> cues = new ArrayList<>();

        @Override public void observe(AutopilotEvent event, long nowMillis) { events.add(event); }
        @Override public void cue(ChatCue cue, long nowMillis) { cues.add(cue); }
        @Override public void tick(ChatPort port, long nowMillis) {}
    }

    private static Decision mine(String goal, String step, int count) {
        return new Decision(List.of(Calls.of("mine", "block", "log", "count", count)), "mine some logs", goal, step, null,
                List.of(), false);
    }

    @Test
    void sameStepWhileBusyIsKept() {
        FakeEngine engine = new FakeEngine();
        AutopilotBrain brain = new AutopilotBrain(SPEC, P, engine, new RecordingChat());
        engine.next = mine("get_wood_tools", "wooden_pickaxe", 3);
        Decision first = brain.decide(new TestSituation().build(), "IDLE", 100, 0);
        assertFalse(first.keepCurrent());
        assertTrue(brain.memory().inFlight().isPresent());

        Situation busy = new TestSituation().busy(true).build();
        assertTrue(brain.decide(busy, "HURT", 120, 1000).keepCurrent(), "a pulse while busy must not restart the same step");
        assertTrue(brain.decide(busy, "URGENT", 140, 2000).keepCurrent());
        assertFalse(brain.decide(busy, "ACTION_ENDED", 160, 3000).keepCurrent());

        engine.next = mine("get_wood_tools", "wooden_pickaxe", 4);
        assertFalse(brain.decide(busy, "HURT", 180, 4000).keepCurrent(), "different arguments are a new plan");
        assertFalse(brain.decide(new TestSituation().build(), "IDLE", 200, 5000).keepCurrent(), "not busy: nothing to keep");
    }

    @Test
    void milestonesAreBraggedOnlyWhenNewlyReached() {
        FakeEngine engine = new FakeEngine();
        RecordingChat chat = new RecordingChat();
        AutopilotBrain brain = new AutopilotBrain(SPEC, P, engine, chat);
        brain.decide(new TestSituation().inv("wooden_pickaxe", 1).build(), "IDLE", 0, 0);
        assertTrue(chat.events.stream().noneMatch(e -> e instanceof AutopilotEvent.Milestone), chat.events.toString());
        brain.decide(new TestSituation().inv("iron_pickaxe", 1).build(), "IDLE", 100, 0);
        brain.decide(new TestSituation().inv("iron_pickaxe", 1).build(), "IDLE", 200, 0);
        List<AutopilotEvent> milestones = chat.events.stream().filter(e -> e instanceof AutopilotEvent.Milestone).toList();
        assertEquals(List.of(new AutopilotEvent.Milestone("stone_pickaxe"), new AutopilotEvent.Milestone("iron_pickaxe")), milestones);
    }

    @Test
    void aGoalChangeIsObservedOnce() {
        FakeEngine engine = new FakeEngine();
        RecordingChat chat = new RecordingChat();
        AutopilotBrain brain = new AutopilotBrain(SPEC, P, engine, chat);
        engine.next = mine("get_wood_tools", "wooden_pickaxe", 3);
        brain.decide(new TestSituation().build(), "IDLE", 0, 0);
        brain.decide(new TestSituation().build(), "ACTION_ENDED", 100, 0);
        List<AutopilotEvent> changes = chat.events.stream().filter(e -> e instanceof AutopilotEvent.GoalChanged).toList();
        assertEquals(List.of(new AutopilotEvent.GoalChanged("get_wood_tools", "get_wood_tools")), changes);
        assertEquals("get_wood_tools", brain.view().goal());
        assertEquals("wooden_pickaxe", brain.view().step());
        assertEquals("mine some logs", brain.view().thought());
    }

    @Test
    void cuesReachTheChat() {
        FakeEngine engine = new FakeEngine();
        RecordingChat chat = new RecordingChat();
        AutopilotBrain brain = new AutopilotBrain(SPEC, P, engine, chat);
        ChatCue cue = new ChatCue("ask_help", Map.of("goal", "farm"), false);
        engine.next = new Decision(List.of(), "stuck", "farm", "till", null, List.of(cue), false);
        brain.decide(new TestSituation().build(), "IDLE", 0, 0);
        assertEquals(List.of(cue), chat.cues);
    }

    @Test
    void failedActionsReachTheMemory() {
        FakeEngine engine = new FakeEngine();
        RecordingChat chat = new RecordingChat();
        AutopilotBrain brain = new AutopilotBrain(SPEC, P, engine, chat);
        engine.next = mine("get_wood_tools", "wooden_pickaxe", 3);
        brain.decide(new TestSituation().build(), "IDLE", 0, 0);
        brain.actionsEnded("failed: mine log: nothing to mine", 50, 0);
        assertEquals(1, brain.memory().failures("get_wood_tools", "wooden_pickaxe"));
        assertTrue(chat.events.contains(new AutopilotEvent.ActionsEnded("failed: mine log: nothing to mine")));
    }

    @Test
    void nightFallIsSentToTheChat() {
        RecordingChat chat = new RecordingChat();
        AutopilotBrain brain = new AutopilotBrain(SPEC, P, new FakeEngine(), chat);
        assertNull(brain.urgent(new Pulse(20, 20, false, null, 0), 0));
        assertEquals("night fell", brain.urgent(new Pulse(20, 20, true, null, 20), 0));
        assertTrue(chat.events.contains(new AutopilotEvent.NightFell()));
    }

    @Test
    void respecKeepsMemoryAndViewShowsPersonality() {
        FakeEngine engine = new FakeEngine();
        AutopilotBrain brain = new AutopilotBrain(SPEC, P, engine, new RecordingChat());
        brain.memory().fail("g", "s", 0, 3);
        Personality other = new Personality("miner", "Miner", "Digs.", Traits.NEUTRAL, Map.of(), Map.of());
        brain.respec(new AutopilotSpec("autopilot-miner", "miner", "groq-llama", 5), other, engine, AutopilotChat.NONE);
        assertEquals(1, brain.memory().failures("g", "s"));
        AutopilotView v = brain.view();
        assertEquals("miner", v.personality());
        assertEquals("Miner", v.personalityName());
        assertEquals("groq-llama", v.chatModel());
        assertEquals(List.of("brave", "greedy", "social", "tidy", "patient"), List.copyOf(v.traits().keySet()));
    }

    @Test
    void decisionOfANextMoveCarriesItsCallsAndFlag() {
        ToolCall c = Calls.of("plant", "seed", "wheat_seeds", "count", 4);
        assertEquals("wheat_seeds", c.args().get("seed").getAsString());
        assertEquals(4, c.args().get("count").getAsInt());
        Decision w = Decision.waiting("idle");
        assertTrue(w.calls().isEmpty());
        assertNull(w.goal());
        assertTrue(w.kept().keepCurrent());
    }
}
