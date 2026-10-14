package com.aiciv.autopilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestsTest {
    private static Optional<AutopilotMemory.Request> parse(String text, boolean mentioned) {
        return Requests.parse(text, "TestBot", mentioned, 77);
    }

    @Test
    void giveMeThreeBread() {
        AutopilotMemory.Request r = parse("TestBot, give me 3 bread", true).orElseThrow();
        assertEquals("GIVE", r.kind());
        assertEquals("bread", r.item());
        assertEquals(3, r.count());
        assertEquals(77, r.tick());
    }

    @Test
    void politeRequestWithAnArticle() {
        AutopilotMemory.Request r = parse("@TestBot can you toss me an apple please", true).orElseThrow();
        assertEquals("GIVE", r.kind());
        assertEquals("apple", r.item());
        assertEquals(1, r.count());
    }

    @Test
    void anyoneGotAsksEveryoneEvenWithoutAMention() {
        AutopilotMemory.Request r = parse("anyone got iron ingot", false).orElseThrow();
        assertEquals("GIVE", r.kind());
        assertEquals("iron_ingot", r.item());
        assertEquals(1, r.count());
        assertTrue(parse("got any bread?", false).isPresent());
    }

    @Test
    void iNeedOnlyCountsWhenMentioned() {
        assertTrue(parse("i need 2 torches", false).isEmpty());
        assertEquals(2, parse("TestBot i need 2 torches", true).orElseThrow().count());
    }

    @Test
    void plainGiveWithoutAMentionIsIgnored() {
        assertTrue(parse("give me bread", false).isEmpty());
    }

    @Test
    void followAndComeNeedAMention() {
        assertEquals("FOLLOW", parse("TestBot follow me", true).orElseThrow().kind());
        assertEquals("COME", parse("TestBot help", true).orElseThrow().kind());
        assertEquals("COME", parse("come here!", true).orElseThrow().kind());
        assertTrue(parse("follow me", false).isEmpty());
        assertTrue(parse("help", false).isEmpty());
    }

    @Test
    void countsAreClamped() {
        assertEquals(16, parse("TestBot give me 99 bread", true).orElseThrow().count());
        assertEquals(1, parse("TestBot give me 0 bread", true).orElseThrow().count());
        assertEquals(16, parse("TestBot give me 99999999999999 bread", true).orElseThrow().count());
    }

    @Test
    void chatterIsNotARequest() {
        assertTrue(parse("TestBot nice house", true).isEmpty());
        assertTrue(parse("", true).isEmpty());
        assertTrue(Requests.parse(null, "TestBot", true, 0).isEmpty());
    }
}
