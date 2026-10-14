package com.aiciv.autopilot.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.Tuning;
import com.aiciv.config.AiCivConfig;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ChatPolicyTest {
    private static final AiCivConfig.AutopilotConfig CFG = new AiCivConfig.AutopilotConfig();

    private static ChatPolicy policy(double social) {
        // Behaviour.tuning: chatCooldownFactor = 1 - 0.5 * social
        Tuning t = new Tuning(3, 48, 8, 0.15, false, 16, true, 1 - 0.5 * social, 0);
        return new ChatPolicy(social, t, CFG);
    }

    @Test
    void cooldownScalesWithSocial() {
        assertEquals(45_000, policy(-1).personalCooldown());
        assertEquals(30_000, policy(0).personalCooldown());
        assertEquals(15_000, policy(1).personalCooldown());
        assertEquals(90_000, policy(-1).llmCooldown());
        assertEquals(30_000, policy(1).llmCooldown());
    }

    @Test
    void lonersOnlyAnswerDieAndGive() {
        ChatPolicy loner = policy(-0.7);
        assertTrue(loner.loner());
        for (String c : List.of("mention", "died", "give", "refuse", "coming", "thanks")) assertTrue(loner.categoryAllowed(c), c);
        for (String c : List.of("greet", "brag", "complain", "ask_help", "other_died", "banter", "night", "goal")) {
            assertFalse(loner.categoryAllowed(c), c);
        }
        assertFalse(policy(-0.69).loner());
        assertTrue(policy(-0.69).categoryAllowed("greet"));
    }

    @Test
    void optionalLinesNeedTheDice() {
        assertTrue(policy(1).optionalSpeaks(fixed(0.999)));
        assertFalse(policy(-1).optionalSpeaks(fixed(0.0)), "social -1 never starts a conversation");
        assertTrue(policy(-0.5).optionalSpeaks(fixed(0.2)) && policy(0).optionalSpeaks(fixed(0.49)));
        assertFalse(policy(0).optionalSpeaks(fixed(0.5)));
        int spoke = 0;
        Random r = new Random(7);
        ChatPolicy neutral = policy(0);
        for (int i = 0; i < 2000; i++) if (neutral.optionalSpeaks(r)) spoke++;
        assertTrue(spoke > 900 && spoke < 1100, "spoke " + spoke);
    }

    @Test
    void kindsAndCooldowns() {
        FakeChatPort port = new FakeChatPort();
        ChatPolicy p = policy(0);
        assertEquals(ChatPolicy.Kind.PRIORITY, ChatPolicy.kind("brag", false));
        assertEquals(ChatPolicy.Kind.PRIORITY, ChatPolicy.kind("died", false));
        assertEquals(ChatPolicy.Kind.PRIORITY, ChatPolicy.kind("goal", true));
        assertEquals(ChatPolicy.Kind.REPLY, ChatPolicy.kind("mention", false));
        assertEquals(ChatPolicy.Kind.OPTIONAL, ChatPolicy.kind("night", false));

        p.spoke(ChatPolicy.Kind.OPTIONAL, 100_000);
        port.lastSpoke = 100_000;
        assertFalse(p.mayNowSpeak(ChatPolicy.Kind.OPTIONAL, 129_999, port));
        assertTrue(p.mayNowSpeak(ChatPolicy.Kind.OPTIONAL, 130_000, port));
        assertTrue(p.mayNowSpeak(ChatPolicy.Kind.PRIORITY, 102_000, port), "priority ignores the personal cooldown");
        assertFalse(p.mayNowSpeak(ChatPolicy.Kind.PRIORITY, 101_000, port), "but not the global gap");
        assertTrue(p.mayNowSpeak(ChatPolicy.Kind.REPLY, 102_000, port));

        p.spoke(ChatPolicy.Kind.REPLY, 200_000);
        port.lastSpoke = 200_000;
        assertFalse(p.mayNowSpeak(ChatPolicy.Kind.REPLY, 204_999, port));
        assertTrue(p.mayNowSpeak(ChatPolicy.Kind.REPLY, 205_000, port));
    }

    @Test
    void banterPairsAreLimited() {
        ChatPolicy p = policy(0);
        assertTrue(p.considerBanter("a|b", 0));
        assertFalse(p.considerBanter("a|b", 59_999), "looked at it less than 1200 ticks ago");
        assertTrue(p.considerBanter("c|d", 1_000), "another pair is independent");
        assertTrue(p.considerBanter("a|b", 60_000));
        p.banterSpoken("a|b", 60_000);
        assertFalse(p.considerBanter("a|b", 120_000));
        assertFalse(p.considerBanter("a|b", 359_999), "spoken less than 6000 ticks ago");
        assertTrue(p.considerBanter("a|b", 360_000));
    }

    @Test
    void llmCooldown() {
        ChatPolicy p = policy(0);
        assertTrue(p.llmCooledDown(0));
        p.llmRequested(1_000);
        assertFalse(p.llmCooledDown(60_999));
        assertTrue(p.llmCooledDown(61_000));
    }

    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override public long nextLong() { return 0; }
            @Override public double nextDouble() { return value; }
        };
    }
}
