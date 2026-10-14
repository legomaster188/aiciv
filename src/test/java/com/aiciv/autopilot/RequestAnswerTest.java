package com.aiciv.autopilot;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.personality.Personalities;
import com.aiciv.autopilot.personality.Personality;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A person's request reaches a busy autopilot: it answers next, or says it is busy, or (a hermit) refuses. Never silence. */
class RequestAnswerTest {
    private static final class RecordingChat implements AutopilotChat {
        final List<ChatCue> cues = new ArrayList<>();

        @Override public void observe(AutopilotEvent event, long nowMillis) {}
        @Override public void cue(ChatCue cue, long nowMillis) { cues.add(cue); }
        @Override public void tick(ChatPort port, long nowMillis) {}
    }

    private final RecordingChat chat = new RecordingChat();

    private AutopilotBrain brain(String personality) {
        Personality p = Personalities.defaults().get(personality).orElseThrow();
        return new AutopilotBrain("Rex", new AutopilotSpec("autopilot-" + personality, personality, null, 5), p, Engines.create(), chat);
    }

    /** What the bot sees: a wooden pickaxe (so iron tools are wanted) plus {@code extra}, and its pending requests. */
    private static Situation seen(AutopilotBrain b, long tick, Sit extra) {
        return extra.name("Rex").inv("wooden_pickaxe", 1).tuning(b.tuning()).requests(b.memory().requests(tick)).tick(tick).build();
    }

    private static void ask(AutopilotBrain b, String text, boolean speakerIsAi, long tick) {
        b.observe(new AutopilotEvent.Heard("Steve", text, true, speakerIsAi), tick, 0);
    }

    private List<String> categories() {
        return chat.cues.stream().map(ChatCue::category).toList();
    }

    @Test
    void aMinerWorkingOnIronToolsGivesAPersonBreadAtOnce() {
        AutopilotBrain b = brain("miner");
        String working = b.decide(seen(b, 900, sit().inv("bread", 40)), "IDLE", 900, 0).goal(); // iron tools or diamonds
        assertNotEquals("social", working);
        ask(b, "Rex give me 3 bread", false, 1000);
        Decision d = b.decide(seen(b, 1000, sit().inv("bread", 40)), "MENTIONED", 1000, 0);
        assertEquals("social", d.goal(), d.thought());
        assertEquals(Calls.of("give", "name", "Steve", "item", "bread", "count", 3), d.calls().get(0));
        assertFalse(b.memory().requests(1000).isEmpty(), "handled only once the give finished");
        b.actionsEnded("finished: give bread x3", 1100, 0);
        assertTrue(b.memory().requests(1100).isEmpty());
        assertEquals(working, b.decide(seen(b, 1110, sit().inv("bread", 37)), "ACTION_ENDED", 1110, 0).goal());
    }

    @Test
    void withNothingToSpareItRefusesPolitely() {
        AutopilotBrain b = brain("miner");
        ask(b, "Rex give me 3 bread", false, 1000);
        Decision d = b.decide(seen(b, 1000, sit()), "MENTIONED", 1000, 0);
        assertEquals("social", d.goal());
        assertTrue(d.calls().isEmpty());
        assertEquals(List.of("refuse"), categories());
        assertTrue(b.memory().requests(1000).isEmpty());
    }

    @Test
    void inDangerItSaysItIsBusyOnce() {
        AutopilotBrain b = brain("miner");
        ask(b, "Rex give me 3 bread", false, 1000);
        Decision d = b.decide(seen(b, 1000, sit().inv("bread", 40).hostile("zombie", 5)), "MENTIONED", 1000, 0);
        assertNotEquals("social", d.goal());
        assertEquals(List.of("busy"), categories());
        assertEquals("Steve", chat.cues.get(0).vars().get("speaker"));
        b.decide(seen(b, 1010, sit().inv("bread", 40).hostile("zombie", 5)), "IDLE", 1010, 0);
        assertEquals(List.of("busy"), categories(), "said once per request");
        Decision later = b.decide(seen(b, 1020, sit().inv("bread", 40)), "IDLE", 1020, 0);
        assertEquals("social", later.goal(), "answered once the danger is gone");
    }

    @Test
    void aHermitRefusesInsteadOfStayingSilent() {
        AutopilotBrain b = brain("hermit");
        ask(b, "Rex give me 3 bread", false, 1000);
        Decision d = b.decide(seen(b, 1000, sit().inv("bread", 40)), "MENTIONED", 1000, 0);
        assertNotEquals("social", d.goal());
        assertEquals(List.of("refuse"), categories());
        assertTrue(b.memory().requests(1000).isEmpty());
    }

    @Test
    void anotherBotsRequestGetsNoFloorAndNoBusyLine() {
        AutopilotBrain b = brain("miner");
        ask(b, "Rex give me 3 bread", true, 1000);
        Decision d = b.decide(seen(b, 1000, sit().inv("bread", 40)), "MENTIONED", 1000, 0);
        assertNotEquals("social", d.goal());
        assertTrue(chat.cues.isEmpty(), chat.cues.toString());
    }

    @Test
    void anAutopilotsOpenAskIsNotARequest() {
        AutopilotBrain b = brain("miner");
        b.observe(new AutopilotEvent.Heard("Ada", "Anyone got a spare bread?", false, true, true), 1000, 0);
        assertTrue(b.memory().requests(1000).isEmpty());
        b.observe(new AutopilotEvent.Heard("Ada", "Anyone got a spare bread?", false, true, false), 1000, 0);
        assertEquals("bread", b.memory().requests(1000).get(0).item(), "an LLM bot's open ask still counts");
    }
}
