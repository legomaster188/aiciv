package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ReplyParserTest {
    static JsonObject response(String messageJson) {
        return JsonParser.parseString("{\"choices\": [{\"message\": " + messageJson + "}]}").getAsJsonObject();
    }

    @Test
    void readsToolCallsWithStringArguments() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": "On my way.", "tool_calls": [
              {"id": "1", "type": "function", "function": {"name": "say", "arguments": "{\\"text\\": \\"hi\\"}"}},
              {"id": "2", "type": "function", "function": {"name": "goTo", "arguments": {"x": 1, "y": 64, "z": 2}}}]}"""));
        assertEquals(2, r.calls().size());
        assertEquals("say", r.calls().get(0).name());
        assertEquals("hi", r.calls().get(0).args().get("text").getAsString());
        assertEquals(64, r.calls().get(1).args().get("y").getAsInt());
        assertEquals("On my way.", r.thought());
        assertNull(r.plainText());
    }

    @Test
    void brokenArgumentsAreFlaggedNotThrown() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": null, "tool_calls": [{"function": {"name": "goTo", "arguments": "{x: oops"}}]}"""));
        assertEquals(1, r.calls().size());
        assertNotNull(r.calls().get(0).argsError());
    }

    @Test
    void contentPartsAreJoined() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": [{"type": "text", "text": "Hello "}, {"type": "text", "text": "there"}]}"""));
        assertEquals("Hello there", r.plainText());
    }

    @Test
    void jsonFallbackInACodeFence() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": "```json\\n[{\\"action\\": \\"say\\", \\"args\\": {\\"text\\": \\"yo\\"}}, {\\"action\\": \\"stop\\"}]\\n```"}"""));
        assertEquals(2, r.calls().size());
        assertEquals("stop", r.calls().get(1).name());
        assertEquals(0, r.calls().get(1).args().size());
        assertNull(r.plainText());
    }

    @Test
    void singleObjectFallback() throws Exception {
        LlmReply r = ReplyParser.parse(response("""
            {"content": "{\\"action\\": \\"goToPlayer\\", \\"args\\": {\\"name\\": \\"Steve\\"}}"}"""));
        assertEquals("goToPlayer", r.calls().get(0).name());
    }

    @Test
    void plainTextWithBracketsStaysText() throws Exception {
        LlmReply r = ReplyParser.parse(response("{\"content\": \"I'll head [north] soon\"}"));
        assertTrue(r.calls().isEmpty());
        assertEquals("I'll head [north] soon", r.plainText());
    }

    @Test
    void emptyMessageMeansNothing() throws Exception {
        LlmReply r = ReplyParser.parse(response("{\"content\": \"  \"}"));
        assertTrue(r.calls().isEmpty());
        assertNull(r.plainText());
    }

    @Test
    void missingChoicesThrow() {
        assertThrows(ReplyFormatException.class, () -> ReplyParser.parse(new JsonObject()));
    }
}
