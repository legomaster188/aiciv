package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.config.AiCivConfig;
import com.aiciv.config.KeyStore;
import com.aiciv.testutil.MutableClock;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmServiceTest {
    @TempDir Path dir;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));

    static final class FakeBackend implements ChatBackend {
        final Deque<BackendResult> results = new ArrayDeque<>();
        final List<JsonObject> bodies = new ArrayList<>();

        @Override
        public CompletableFuture<BackendResult> send(JsonObject body) {
            bodies.add(body);
            BackendResult r = results.isEmpty() ? ok("{\"content\": \"hi\"}") : results.poll();
            return CompletableFuture.completedFuture(r);
        }
    }

    /** Throws before returning a future, as HttpChatBackend does for a URI it cannot send to. */
    static final class ThrowingBackend implements ChatBackend {
        @Override
        public CompletableFuture<BackendResult> send(JsonObject body) {
            throw new IllegalArgumentException("invalid URI scheme localhost");
        }
    }

    static BackendResult ok(String messageJson) {
        return new BackendResult.Ok(JsonParser.parseString("{\"choices\": [{\"message\": " + messageJson + "}]}").getAsJsonObject());
    }

    /** Model "ma" on provider "a" falls back to "mb" on provider "b". */
    static AiCivConfig config(int rpdA) throws Exception {
        String json = """
            {"providers": {"a": {"baseUrl": "http://a", "rpd": %d}, "b": {"baseUrl": "http://b"}},
             "models": {"ma": {"provider": "a", "model": "model-a", "fallback": "mb"},
                        "mb": {"provider": "b", "model": "model-b"}},
             "defaultModel": "ma"}""".formatted(rpdA);
        return AiCivConfig.GSON.fromJson(json, AiCivConfig.class);
    }

    LlmService.Outcome send(LlmService s, String model) throws Exception {
        return s.trySend(model, "sys", "user", new JsonArray()).orElseThrow().get();
    }

    @Test
    void replyComesFromThePrimaryWithTheRightBody() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        JsonArray tools = JsonParser.parseString("[{\"type\": \"function\", \"function\": {\"name\": \"say\"}}]").getAsJsonArray();
        LlmService.Outcome o = s.trySend("ma", "sys", "user", tools).orElseThrow().get();
        LlmService.Outcome.Reply r = assertInstanceOf(LlmService.Outcome.Reply.class, o);
        assertEquals("a", r.route().providerKey());
        assertEquals("hi", r.reply().plainText());
        JsonObject body = a.bodies.get(0);
        assertEquals("model-a", body.get("model").getAsString());
        assertEquals(1000, body.get("max_tokens").getAsInt());
        assertEquals("auto", body.get("tool_choice").getAsString());
        assertEquals("sys", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void rateLimitedPrimaryHandsOverToFallback() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        a.results.add(new BackendResult.RateLimited(Duration.ofSeconds(30)));
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        assertInstanceOf(LlmService.Outcome.RateLimited.class, send(s, "ma"));
        assertEquals("b", s.peek("ma").orElseThrow().providerKey());
        LlmService.Outcome next = send(s, "ma");
        assertEquals("b", next.route().providerKey());
        clock.advance(Duration.ofSeconds(30));
        assertEquals("a", s.peek("ma").orElseThrow().providerKey());
    }

    @Test
    void authFailureDisablesAndShowsInBudget() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        a.results.add(new BackendResult.Failed("HTTP 401 (key rejected): no", true));
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        assertInstanceOf(LlmService.Outcome.Failed.class, send(s, "ma"));
        assertEquals("b", s.peek("ma").orElseThrow().providerKey());
        String budget = String.join("\n", s.budgetLines());
        assertTrue(budget.contains("a: 1 used today, no daily limit, disabled"), budget);
        assertTrue(s.providerDisabled("a"));
        assertFalse(s.providerDisabled("b"));
    }

    @Test
    void dailyCapFallsBack() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        LlmService s = new LlmService(config(1), Map.of("a", a, "b", b), clock);
        assertEquals("a", send(s, "ma").route().providerKey());
        assertEquals("b", send(s, "ma").route().providerKey());
    }

    @Test
    void noRouteMeansEmpty() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        a.results.add(new BackendResult.Failed("HTTP 401 (key rejected)", true));
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        send(s, "ma");
        assertTrue(s.trySend("unknown", "s", "u", new JsonArray()).isEmpty());
        b.results.add(new BackendResult.Failed("HTTP 401 (key rejected)", true));
        send(s, "mb");
        assertTrue(s.peek("ma").isEmpty());
        assertTrue(s.trySend("ma", "s", "u", new JsonArray()).isEmpty());
    }

    @Test
    void garbageReplyCountsAsFailure() throws Exception {
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        a.results.add(new BackendResult.Ok(new JsonObject()));
        LlmService s = new LlmService(config(0), Map.of("a", a, "b", b), clock);
        LlmService.Outcome.Failed f = assertInstanceOf(LlmService.Outcome.Failed.class, send(s, "ma"));
        assertTrue(f.reason().contains("no choices"));
    }

    @Test
    void missingKeyDisablesProviderAndFallsBack() throws Exception {
        AiCivConfig c = config(0);
        c.providers.get("a").key = "gemini";
        KeyStore keys = KeyStore.load(dir.resolve("keys.json"), name -> null);
        LlmService s = LlmService.create(c, keys, dir, clock);
        assertEquals("b", s.peek("ma").orElseThrow().providerKey());
        String budget = String.join("\n", s.budgetLines());
        assertTrue(budget.contains("no key 'gemini'"), budget);
        assertTrue(Files.exists(dir.resolve("keys.json")));
    }

    @Test
    void timeoutComesFromConfig() throws Exception {
        AiCivConfig c = config(0);
        c.providers.get("a").timeoutSeconds = 7;
        c.providers.get("b").timeoutSeconds = 1; // floored to 5
        KeyStore keys = KeyStore.load(dir.resolve("keys.json"), name -> null);
        LlmService s = LlmService.create(c, keys, dir, clock);
        assertEquals(Duration.ofSeconds(7), s.timeoutFor("a"));
        assertEquals(Duration.ofSeconds(5), s.timeoutFor("b"));
    }

    @Test
    void extraFieldsAreMerged() throws Exception {
        AiCivConfig c = config(0);
        c.models.get("ma").extra.addProperty("reasoning_effort", "none");
        c.models.get("ma").maxTokens = 222;
        FakeBackend a = new FakeBackend(), b = new FakeBackend();
        LlmService s = new LlmService(c, Map.of("a", a, "b", b), clock);
        send(s, "ma");
        assertEquals("none", a.bodies.get(0).get("reasoning_effort").getAsString());
        assertEquals(222, a.bodies.get(0).get("max_tokens").getAsInt());
        assertFalse(a.bodies.get(0).has("tools"));
    }

    @Test
    void synchronousSendFailureFreesTheSlotAndIsAFailure() throws Exception {
        AiCivConfig c = config(0);
        c.providers.get("a").maxInFlight = 1;
        FakeBackend b = new FakeBackend();
        LlmService s = new LlmService(c, Map.of("a", new ThrowingBackend(), "b", b), clock);
        for (int i = 0; i < 2; i++) {
            LlmService.Outcome.Failed f = assertInstanceOf(LlmService.Outcome.Failed.class, send(s, "ma"));
            assertEquals("a", f.route().providerKey());
            assertTrue(f.reason().contains("invalid URI scheme localhost"), f.reason());
        }
        assertEquals("a", s.peek("ma").orElseThrow().providerKey());
        assertTrue(b.bodies.isEmpty());
        assertTrue(String.join("\n", s.budgetLines()).contains("invalid URI scheme localhost"));
    }

    @Test
    void badBaseUrlDisablesOnlyThatProvider() throws Exception {
        AiCivConfig c = config(0);
        c.providers.get("a").baseUrl = "http://a b";
        KeyStore keys = KeyStore.load(dir.resolve("keys.json"), name -> null);
        LlmService s = LlmService.create(c, keys, dir, clock);
        assertEquals("b", s.peek("ma").orElseThrow().providerKey());
        String budget = String.join("\n", s.budgetLines());
        assertTrue(budget.contains("a: 0 used today, no daily limit, disabled: invalid baseUrl 'http://a b'"), budget);
        assertTrue(s.providerDisabled("a"));
        assertFalse(s.providerDisabled("b"));
        assertEquals(List.of("a", "b"), s.routeProviders("ma"));
        assertEquals(List.of("b"), s.routeProviders("mb"));
        assertEquals(List.of(), s.routeProviders("nope"));
        assertTrue(s.providerStatus("a").startsWith("disabled: invalid baseUrl 'http://a b'"), s.providerStatus("a"));
    }

    static final List<com.aiciv.brain.MenuOption> MENU = List.of(
            new com.aiciv.brain.MenuOption("mine_oak_log", "mine 6 oak_log", List.of()),
            new com.aiciv.brain.MenuOption("wait", "do nothing", List.of()));

    static AiCivConfig menuConfig() {
        return AiCivConfig.GSON.fromJson("""
            {"providers": {"a": {"baseUrl": "http://a"}},
             "models": {"menu": {"provider": "a", "model": "model-a", "decisionMode": "menu"}},
             "defaultModel": "menu"}""", AiCivConfig.class);
    }

    @Test
    void menuModeUsesChooseTool() throws Exception {
        FakeBackend a = new FakeBackend();
        a.results.add(ok("{\"tool_calls\": [{\"function\": {\"name\": \"choose\", \"arguments\": \"{\\\"option\\\": \\\"wait\\\"}\"}}]}"));
        LlmService s = new LlmService(menuConfig(), Map.of("a", a), clock);
        assertTrue(s.menuMode("menu"));
        LlmService.Outcome.Chosen o = assertInstanceOf(LlmService.Outcome.Chosen.class,
                s.trySendMenu("menu", "sys", "user text", MENU).orElseThrow().get());
        assertEquals("wait", o.reply().optionId());
        JsonObject body = a.bodies.get(0);
        JsonArray tools = body.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        JsonObject fn = tools.get(0).getAsJsonObject().getAsJsonObject("function");
        assertEquals("choose", fn.get("name").getAsString());
        JsonObject option = fn.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("option");
        assertEquals("string", option.get("type").getAsString());
        assertEquals(JsonParser.parseString("[\"mine_oak_log\", \"wait\"]"), option.getAsJsonArray("enum"));
        assertEquals("option", fn.getAsJsonObject("parameters").getAsJsonArray("required").get(0).getAsString());
        assertEquals("choose", body.getAsJsonObject("tool_choice").getAsJsonObject("function").get("name").getAsString());
        String user = body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(user.startsWith("user text") && user.contains("mine_oak_log - mine 6 oak_log"), user);
    }

    @Test
    void menuRepliesInJsonOrBareTextAreUnderstood() throws Exception {
        for (String content : List.of("{\"content\": \"{\\\"option\\\": \\\"mine_oak_log\\\"}\"}",
                "{\"content\": \"mine_oak_log\"}", "{\"content\": \"I think `mine_oak_log` is best.\"}")) {
            FakeBackend a = new FakeBackend();
            a.results.add(ok(content));
            LlmService s = new LlmService(menuConfig(), Map.of("a", a), clock);
            LlmService.Outcome.Chosen o = assertInstanceOf(LlmService.Outcome.Chosen.class,
                    s.trySendMenu("menu", "sys", "u", MENU).orElseThrow().get());
            assertEquals("mine_oak_log", o.reply().optionId(), content);
        }
    }

    @Test
    void anEmptyMenuReplyFails() throws Exception {
        FakeBackend a = new FakeBackend();
        a.results.add(ok("{}"));
        LlmService s = new LlmService(menuConfig(), Map.of("a", a), clock);
        assertInstanceOf(LlmService.Outcome.Failed.class, s.trySendMenu("menu", "sys", "u", MENU).orElseThrow().get());
    }

    @Test
    void aTypesafeProviderIsNotUsedForChatRequests() throws Exception {
        AiCivConfig c = AiCivConfig.GSON.fromJson("""
            {"providers": {"jev": {"type": "typesafe", "baseUrl": "http://localhost:1"}},
             "models": {"jev": {"provider": "jev", "model": "typesafe/jev-1.13"}}, "defaultModel": "jev"}""", AiCivConfig.class);
        LlmService s = new LlmService(c, Map.of(), Map.of("jev", new TypesafeBackend("http://localhost:1", null, null, Duration.ofSeconds(5))), clock);
        assertTrue(s.menuMode("jev"));
        assertTrue(s.peek("jev").isPresent());
        assertTrue(s.trySend("jev", "sys", "user", new JsonArray()).isEmpty());
    }
    /** The scheduler peeks before it sends; a peek must never promise a route that trySend cannot use. */
    @Test
    void aToolsModelNeverPeeksAtATypesafeFallback() throws Exception {
        AiCivConfig c = AiCivConfig.GSON.fromJson("""
            {"providers": {"a": {"baseUrl": "http://a", "rpd": 1}, "jev": {"type": "typesafe", "baseUrl": "http://localhost:1"}},
             "models": {"ma": {"provider": "a", "model": "model-a", "fallback": "jev"},
                        "menu": {"provider": "a", "model": "model-a", "decisionMode": "menu", "fallback": "jev"},
                        "jev": {"provider": "jev", "model": "typesafe/jev-1.13"}},
             "defaultModel": "ma"}""", AiCivConfig.class);
        LlmService s = new LlmService(c, Map.of("a", new FakeBackend()),
                Map.of("jev", new TypesafeBackend("http://localhost:1", null, null, Duration.ofSeconds(5))), clock);
        send(s, "ma"); // uses provider a's only request today
        assertTrue(s.trySend("ma", "sys", "user", new JsonArray()).isEmpty());
        assertTrue(s.peek("ma").isEmpty(), "peek offered the typesafe fallback to a tools model");
        assertEquals("jev", s.peek("menu").orElseThrow().providerKey());
    }
}
