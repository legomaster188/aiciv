package com.aiciv.autopilot.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.AutopilotEvent;
import com.aiciv.autopilot.AutopilotSpec;
import com.aiciv.autopilot.ChatCue;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import com.aiciv.autopilot.personality.Personality;
import com.aiciv.autopilot.personality.Traits;
import com.aiciv.config.AiCivConfig;
import com.aiciv.llm.LlmReply;
import com.aiciv.llm.LlmService;
import com.aiciv.perception.Sighting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class TemplateChatTest {
    private static final AiCivConfig.AutopilotConfig CFG = new AiCivConfig.AutopilotConfig();
    private static final LlmService.Route ROUTE = new LlmService.Route("cheap", "p", "m");
    private static final long T0 = 1_000_000;

    private static Personality person(double social) {
        return new Personality("miner", "Miner", "Happiest underground.", new Traits(0, 0, social, 0, 0), Map.of(), Map.of());
    }

    private static TemplateChat chat(double social, String chatModel) {
        Personality p = person(social);
        Tuning t = new Tuning(3, 48, 8, 0.15, false, 16, true, 1 - 0.5 * social, 0);
        return new TemplateChat(new AutopilotSpec("autopilot-miner", "miner", chatModel, 5), p, t, CFG,
                PhraseBook.forPersonality(p), new Random(1));
    }

    private static AutopilotEvent.Heard mention(String speaker) {
        return new AutopilotEvent.Heard(speaker, "Ada how are you", true, false);
    }

    private static LlmService.Outcome reply(String text) {
        return new LlmService.Outcome.Reply(ROUTE, new LlmReply(List.of(), null, text, "stop", null));
    }

    @Test
    void milestoneBragsOnTheNextTick() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(0, null);
        c.observe(new AutopilotEvent.Milestone("iron_pickaxe"), T0);
        c.tick(port, T0 + 50);
        assertEquals(1, port.said.size());
        assertTrue(port.said.get(0).contains("iron pickaxe"), port.said.get(0));
        assertFalse(port.said.get(0).contains("{"));
        assertEquals(1, port.logs.size());
        assertEquals("autopilot-chat", port.logField(0, "mode"));
        assertEquals("brag", port.logField(0, "event"));
        assertEquals("template", port.logField(0, "source"));
        assertNull(port.logField(0, "model"));
        assertEquals(port.said.get(0), port.logField(0, "text"));
        assertNotNull(port.logField(0, "time"));
        c.tick(port, T0 + 100);
        assertEquals(1, port.said.size(), "the cue was used up");
    }

    @Test
    void mentionReplyBypassesTheCooldownButNotTheFiveSecondGap() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        c.observe(new AutopilotEvent.Spawned(), T0);
        c.tick(port, T0);
        assertEquals(1, port.said.size(), "a greeting starts the 30 s cooldown");

        c.observe(mention("Bob"), T0 + 3000);
        c.tick(port, T0 + 3000);
        assertEquals(2, port.said.size(), "a reply ignores the personal cooldown");
        assertEquals("mention", port.logField(1, "event"));
        assertTrue(port.said.get(1).contains("Bob"), port.said.get(1));

        c.observe(mention("Bob"), T0 + 6000);
        c.tick(port, T0 + 6000);
        assertEquals(2, port.said.size(), "3 s after the last reply is too soon");
        c.tick(port, T0 + 8000);
        assertEquals(3, port.said.size(), "5 s after the last reply the waiting cue is said");
    }

    @Test
    void globalGapHoldsAcrossBots() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat first = chat(0, null);
        TemplateChat second = chat(0, null);
        first.observe(new AutopilotEvent.Milestone("iron_ingot"), T0);
        second.observe(new AutopilotEvent.Milestone("iron_ingot"), T0);
        first.tick(port, T0);
        second.tick(port, T0);
        assertEquals(1, port.said.size());
        second.tick(port, T0 + 1999);
        assertEquals(1, port.said.size());
        second.tick(port, T0 + 2000);
        assertEquals(2, port.said.size());
    }

    @Test
    void aCueWaitsTenSecondsAtMost() {
        FakeChatPort port = new FakeChatPort();
        port.lastSpoke = T0 + 9000; // somebody else keeps the gap closed
        port.gapMillis = 3000;
        TemplateChat c = chat(0, null);
        c.observe(new AutopilotEvent.Milestone("iron_ingot"), T0);
        c.tick(port, T0 + 10_000);
        assertTrue(port.said.isEmpty());
        c.tick(port, T0 + 12_000);
        assertTrue(port.said.isEmpty(), "the cue was dropped after 10 s");
    }

    @Test
    void lonerNeverGreetsButStillAnswers() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(-1, null);
        c.observe(new AutopilotEvent.Spawned(), T0);
        c.observe(new AutopilotEvent.PlayerJoined("Bob", false), T0);
        c.observe(new AutopilotEvent.Milestone("iron_ingot"), T0);
        c.observe(new AutopilotEvent.NightFell(), T0);
        c.tick(port, T0 + 50);
        assertTrue(port.said.isEmpty(), "said " + port.said);
        c.observe(mention("Bob"), T0 + 100);
        c.tick(port, T0 + 150);
        assertEquals(1, port.said.size());
    }

    @Test
    void greetingNamesTheNewcomerOrNobody() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        c.observe(new AutopilotEvent.Spawned(), T0);
        c.tick(port, T0);
        assertFalse(port.said.get(0).contains("{"));
        FakeChatPort other = new FakeChatPort();
        TemplateChat d = chat(1, null);
        d.observe(new AutopilotEvent.PlayerJoined("Zed", true), T0);
        d.tick(other, T0);
        assertEquals(1, other.said.size());
    }

    @Test
    void doesNotRepeatItselfRightAway() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        long now = T0;
        for (int i = 0; i < 4; i++) {
            c.cue(new ChatCue("night", Map.of(), true), now);
            c.tick(port, now);
            now += 3000;
        }
        assertEquals(4, port.said.size());
        for (int i = 1; i < 4; i++) assertNotEquals(port.said.get(i - 1), port.said.get(i));
    }

    @Test
    void aCueWithAMissingVariableIsSkippedNotSaidWithAHole() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        c.cue(new ChatCue("ask_help", Map.of("goal", "get_food"), false), T0);
        c.tick(port, T0);
        assertEquals(1, port.said.size());
        assertFalse(port.said.get(0).contains("{"));
        assertFalse(port.said.get(0).toLowerCase().contains("null"));
    }

    @Test
    void banterWithANearbyAutopilot() {
        FakeChatPort port = new FakeChatPort();
        port.models.put("Bob", "gemini-flash");
        port.situation = situation(new Sighting("Bob [AI]", 1, 9, "N"), new Sighting("Cy [AI]", 1, 40, "S"), new Sighting("Dee [human]", 1, 3, "E"));
        TemplateChat c = chat(1, null);
        c.tick(port, T0);
        assertEquals(1, port.said.size());
        assertEquals("banter", port.logField(0, "event"));
        assertFalse(port.said.get(0).contains("{"));
        c.tick(port, T0 + 40_000);
        c.tick(port, T0 + 100_000);
        assertEquals(1, port.said.size(), "the same pair is not teased again within 6000 ticks");
        c.tick(port, T0 + 400_000);
        assertEquals(2, port.said.size());
    }

    @Test
    void llmFailureFallsBackToTemplate() {
        FakeChatPort port = new FakeChatPort();
        port.llmReady = true;
        port.nextSend = Optional.of(CompletableFuture.completedFuture(new LlmService.Outcome.Failed(ROUTE, "boom")));
        TemplateChat c = chat(1, "cheap");
        c.observe(mention("Bob"), T0);
        c.tick(port, T0);
        assertEquals(1, port.sends);
        c.tick(port, T0 + 50);
        assertEquals(1, port.said.size());
        assertEquals("template", port.logField(0, "source"));
        assertEquals("cheap", port.logField(0, "model"));
        assertTrue(port.said.get(0).contains("Bob"));
    }

    @Test
    void rateLimitedOutcomeAlsoFallsBack() {
        FakeChatPort port = new FakeChatPort();
        port.llmReady = true;
        port.nextSend = Optional.of(CompletableFuture.completedFuture(new LlmService.Outcome.RateLimited(ROUTE)));
        TemplateChat c = chat(1, "cheap");
        c.observe(new AutopilotEvent.Milestone("diamond"), T0);
        c.tick(port, T0);
        c.tick(port, T0 + 50);
        assertEquals(1, port.said.size());
        assertEquals("template", port.logField(0, "source"));
    }

    @Test
    void llmSuccessSaysTheCleanedModelLine() {
        FakeChatPort port = new FakeChatPort();
        port.llmReady = true;
        port.nextSend = Optional.of(CompletableFuture.completedFuture(reply("\"Ada: Look at this shiny thing!\"\nextra")));
        TemplateChat c = chat(1, "cheap");
        c.observe(new AutopilotEvent.Milestone("diamond"), T0);
        c.tick(port, T0);
        assertTrue(port.said.isEmpty(), "the line is said when the model answered, not before");
        c.tick(port, T0 + 50);
        assertEquals(List.of("Look at this shiny thing!"), port.said);
        assertEquals("llm", port.logField(0, "source"));
        assertEquals("cheap", port.logField(0, "model"));
        assertTrue(port.sentUsers.get(0).contains("A line you might say: \""), port.sentUsers.get(0));
        assertTrue(port.sentUsers.get(0).contains("you just got diamond"));
        assertTrue(port.sentSystems.get(0).startsWith("You are Ada,"));
    }

    @Test
    void aSlowButFreshModelReplyIsStillSaid() {
        FakeChatPort port = new FakeChatPort();
        port.llmReady = true;
        CompletableFuture<LlmService.Outcome> future = new CompletableFuture<>();
        port.nextSend = Optional.of(future);
        TemplateChat c = chat(1, "cheap");
        c.observe(new AutopilotEvent.Milestone("diamond"), T0);
        c.tick(port, T0);
        c.tick(port, T0 + 8000);
        assertTrue(port.said.isEmpty());
        future.complete(reply("Diamonds!"));
        c.tick(port, T0 + 9000);
        assertEquals(List.of("Diamonds!"), port.said);
    }

    @Test
    void lateLlmReplyIsDropped() {
        FakeChatPort port = new FakeChatPort();
        port.llmReady = true;
        CompletableFuture<LlmService.Outcome> future = new CompletableFuture<>();
        port.nextSend = Optional.of(future);
        TemplateChat c = chat(1, "cheap");
        c.observe(new AutopilotEvent.Milestone("diamond"), T0);
        c.tick(port, T0);
        future.complete(reply("Diamonds!"));
        c.tick(port, T0 + 21_000);
        c.tick(port, T0 + 22_000);
        assertTrue(port.said.isEmpty(), "said " + port.said);
        assertTrue(port.logs.isEmpty());
    }

    @Test
    void aModelThatNeverAnswersDoesNotBlockChatForever() {
        FakeChatPort port = new FakeChatPort();
        port.llmReady = true;
        port.nextSend = Optional.of(new CompletableFuture<>());
        TemplateChat c = chat(1, "cheap");
        c.observe(new AutopilotEvent.Milestone("diamond"), T0);
        c.tick(port, T0);
        port.llmReady = false;
        c.observe(new AutopilotEvent.Milestone("iron_ingot"), T0 + 25_000);
        c.tick(port, T0 + 25_000);
        assertEquals(1, port.said.size());
        assertEquals("template", port.logField(0, "source"));
    }

    @Test
    void noRouteMeansTheTemplateAtOnce() {
        FakeChatPort port = new FakeChatPort();
        port.llmReady = true; // ready, but trySend finds nothing
        TemplateChat c = chat(1, "cheap");
        c.observe(new AutopilotEvent.Milestone("diamond"), T0);
        c.tick(port, T0);
        assertEquals(1, port.sends);
        assertEquals(1, port.said.size());
        assertEquals("template", port.logField(0, "source"));
    }

    @Test
    void llmNotReadyMeansTheTemplateImmediately() {
        FakeChatPort port = new FakeChatPort();
        port.llmReady = false;
        TemplateChat c = chat(1, "cheap");
        c.observe(new AutopilotEvent.Milestone("diamond"), T0);
        c.tick(port, T0);
        assertEquals(0, port.sends);
        assertEquals(1, port.said.size());
        assertEquals("template", port.logField(0, "source"));
    }

    @Test
    void onlyTheWorthwhileCategoriesAskTheModelAndNotTooOften() {
        FakeChatPort port = new FakeChatPort();
        port.llmReady = true;
        port.nextSend = Optional.of(CompletableFuture.completedFuture(reply("Cool!")));
        TemplateChat c = chat(1, "cheap");
        c.cue(new ChatCue("night", Map.of(), true), T0);
        c.tick(port, T0);
        assertEquals(0, port.sends, "night is always a template");
        assertEquals(1, port.said.size());

        c.observe(new AutopilotEvent.Milestone("diamond"), T0 + 3000);
        c.tick(port, T0 + 3000);
        c.tick(port, T0 + 3050);
        assertEquals(1, port.sends);
        assertEquals(2, port.said.size());

        c.observe(new AutopilotEvent.Milestone("emerald"), T0 + 6000);
        c.tick(port, T0 + 6000);
        assertEquals(1, port.sends, "the model is asked at most every 60 s x factor");
        assertEquals(3, port.said.size());
        assertEquals("template", port.logField(2, "source"));
    }

    @Test
    void mentionThatIsARequestIsLeftToThePlanner() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        c.observe(new AutopilotEvent.Heard("Bob", "Ada give me 3 bread", true, false), T0);
        c.tick(port, T0);
        assertEquals(0, port.said.size(), "the planner answers it with give / refuse / coming");
    }

    @Test
    void mentionThatIsNotARequestIsAnswered() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        c.observe(new AutopilotEvent.Heard("Bob", "Ada hello", true, false), T0);
        c.tick(port, T0);
        assertEquals(1, port.said.size());
        assertEquals("mention", port.logField(0, "event"));
    }

    @Test
    void aFloodOfOptionalCuesNeverEvictsAWaitingMention() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        c.observe(new AutopilotEvent.Spawned(), T0);
        c.tick(port, T0); // the greeting starts the global gap, so everything below waits
        c.observe(mention("Bob"), T0 + 100);
        for (int i = 0; i < 30; i++) {
            c.cue(new ChatCue("goal", Map.of("goal", "explore" + i), false), T0 + 100 + i);
            c.cue(new ChatCue("night", Map.of(), false), T0 + 100 + i);
        }
        c.tick(port, T0 + 6000);
        assertEquals(2, port.said.size(), port.said.toString());
        assertEquals("mention", port.logField(1, "event"));
    }

    @Test
    void anotherAutopilotCannotStartAPingPong() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        c.observe(new AutopilotEvent.Heard("Bob", "Ada hi", true, true), T0);
        c.tick(port, T0);
        assertEquals(1, port.said.size());
        c.observe(new AutopilotEvent.Heard("Bob", "Ada hi again", true, true), T0 + 10_000);
        c.tick(port, T0 + 10_000);
        assertEquals(1, port.said.size(), "no second answer to the same bot within a minute");
        c.observe(new AutopilotEvent.Heard("Bob", "Ada hi again", true, true), T0 + 60_000);
        c.tick(port, T0 + 60_000);
        assertEquals(2, port.said.size());
        c.observe(new AutopilotEvent.Heard("Cy", "Ada hi", true, true), T0 + 70_000);
        c.tick(port, T0 + 70_000);
        assertEquals(3, port.said.size(), "another bot is answered");
    }

    @Test
    void messagesFromOtherEventsAreSaid() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        List<String> events = new ArrayList<>();
        long now = T0;
        for (AutopilotEvent e : List.of(new AutopilotEvent.Died("fell from a high place"),
                new AutopilotEvent.Received("Bob", "bread x3"),
                new AutopilotEvent.OtherDied("Cy", "was slain by Zombie"),
                new AutopilotEvent.Hurt("Zombie", 6),
                new AutopilotEvent.GoalChanged("get_food", "find something to eat"))) {
            c.observe(e, now);
            c.tick(port, now);
            events.add(port.logs.isEmpty() ? "?" : port.logField(port.logs.size() - 1, "event"));
            now += 31_000;
        }
        assertEquals(List.of("died", "thanks", "other_died", "complain", "goal"), events);
        for (String said : port.said) assertFalse(said.contains("{"), said);
    }

    @Test
    void aReloadKeepsTheChatThrottles() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat before = chat(1, null); // social 1: optional lines always pass the dice, 15 s apart
        before.observe(new AutopilotEvent.GoalChanged("farm", "farm"), T0);
        before.tick(port, T0);
        assertEquals(1, port.said.size());

        TemplateChat fresh = chat(1, null);
        fresh.observe(new AutopilotEvent.GoalChanged("explore", "explore"), T0 + 5_000);
        FakeChatPort freshPort = new FakeChatPort();
        fresh.tick(freshPort, T0 + 5_000);
        assertEquals(1, freshPort.said.size(), "a chat built from scratch would speak at once");
        TemplateChat reloaded = chat(1, null);
        reloaded.carryOver(before);
        FakeChatPort after = new FakeChatPort();
        reloaded.observe(new AutopilotEvent.GoalChanged("explore", "explore"), T0 + 5_000);
        reloaded.tick(after, T0 + 5_000);
        assertTrue(after.said.isEmpty(), "spoke 5 s after the last line, right after a reload: " + after.said);
        reloaded.observe(new AutopilotEvent.GoalChanged("farm", "farm"), T0 + 15_000);
        reloaded.tick(after, T0 + 15_000);
        assertEquals(1, after.said.size(), "the cooldown still ends");
    }

    @Test
    void hurtAboveTenHealthIsNotWorthAComplaint() {
        FakeChatPort port = new FakeChatPort();
        TemplateChat c = chat(1, null);
        c.observe(new AutopilotEvent.Hurt("Zombie", 11), T0);
        c.tick(port, T0);
        assertTrue(port.said.isEmpty());
    }

    private static Situation situation(Sighting... players) {
        return new Situation("Ada", "overworld", 0, 64, 0, 20, 20, false, 1000, Map.of(), java.util.Set.of(), null, 30,
                List.of(), List.of(players), List.of(), List.of(), List.of(), java.util.Set.of(), List.of(), java.util.Set.of(),
                null, false, java.util.Set.of(), Map.of(), List.of(), Tuning.DEFAULT, null);
    }
}
