package com.aiciv.llm;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.brain.MenuOption;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TypesafeBackendTest {
    static final List<MenuOption> OPTIONS = List.of(
            new MenuOption("mine_oak_log", "mine 6 oak_log", List.of()),
            new MenuOption("eat", "eat food", List.of()),
            new MenuOption("wait", "do nothing", List.of()));
    /** What OpenRouter's /api/alpha/decisions really returned for Jev (2026-10-11). */
    static final String LIVE = "{\"model\":\"typesafe/jev-1.13-20260917\",\"answers\":{\"action\":{\"type\":\"choice\",\"choice\":\"pillar\","
            + "\"probabilities\":{\"fight\":0.02,\"flee\":0.44,\"pillar\":0.54},\"confidence\":0.31}},"
            + "\"usage\":{\"input_tokens\":369,\"output_tokens\":39,\"cost\":0.000015498},\"id\":\"gen-dec-1\",\"provider\":\"TypeSafe\"}";

    HttpServer server;
    volatile int status = 200;
    volatile String body = LIVE;
    volatile long delayMillis;
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { }
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    BackendResult call(String path, String key, Duration timeout) throws Exception {
        return new TypesafeBackend(base(), path, key, timeout).choose("jev-latest", "state text", OPTIONS, "pick one").get(10, SECONDS);
    }

    @Test
    void postsTheQuestionToTheDefaultPathWithTheKey() throws Exception {
        assertInstanceOf(BackendResult.Ok.class, call("/v1/systemone", "secret", Duration.ofSeconds(5)));
        assertEquals("/v1/systemone", paths.get(0));
        assertEquals("Bearer secret", auth.get(0));
        JsonObject sent = JsonParser.parseString(requests.get(0)).getAsJsonObject();
        assertEquals("jev-latest", sent.get("model").getAsString());
        assertEquals("state text", sent.get("state").getAsString());
        JsonObject action = sent.getAsJsonObject("questions").getAsJsonObject("action");
        assertEquals("choice", action.get("type").getAsString());
        assertEquals("pick one", action.get("instructions").getAsString());
        JsonObject criteria = action.getAsJsonObject("criteria");
        assertEquals(3, criteria.size());
        assertEquals("mine 6 oak_log", criteria.get("mine_oak_log").getAsString());
    }

    @Test
    void aCustomDecisionsPathIsUsed() throws Exception {
        call("/api/alpha/decisions", "k", Duration.ofSeconds(5));
        assertEquals("/api/alpha/decisions", paths.get(0));
    }

    @Test
    void aNullPathMeansTheDefault() throws Exception {
        call(null, "k", Duration.ofSeconds(5));
        assertEquals("/v1/systemone", paths.get(0));
    }

    @Test
    void theLiveOpenRouterReplyParses() throws Exception {
        BackendResult.Ok ok = assertInstanceOf(BackendResult.Ok.class, call("/api/alpha/decisions", "k", Duration.ofSeconds(5)));
        MenuReply r = TypesafeBackend.parse(ok.body());
        assertEquals("pillar", r.optionId());
        assertEquals(Map.of("fight", 0.02, "flee", 0.44, "pillar", 0.54), r.probabilities());
        assertEquals(0.31, r.confidence());
    }

    @Test
    void answersWithoutTheWrapperAndWithAnswerFieldParse() throws Exception {
        MenuReply r = TypesafeBackend.parse(JsonParser.parseString("{\"action\":{\"answer\":\"eat\"}}").getAsJsonObject());
        assertEquals("eat", r.optionId());
        assertTrue(r.probabilities().isEmpty());
        assertNull(r.confidence());
    }

    @Test
    void valueAndOptionFieldsAreAcceptedToo() throws Exception {
        assertEquals("a", TypesafeBackend.parse(JsonParser.parseString("{\"answers\":{\"action\":{\"value\":\"a\"}}}").getAsJsonObject()).optionId());
        assertEquals("b", TypesafeBackend.parse(JsonParser.parseString("{\"answers\":{\"action\":{\"option\":\"b\"}}}").getAsJsonObject()).optionId());
    }

    @Test
    void repliesWithNoChoiceAreRejected() {
        assertThrows(ReplyFormatException.class, () -> TypesafeBackend.parse(JsonParser.parseString("{\"answers\":{}}").getAsJsonObject()));
        assertThrows(ReplyFormatException.class, () -> TypesafeBackend.parse(JsonParser.parseString("{\"answers\":{\"action\":{\"choice\":3}}}").getAsJsonObject()));
    }

    @Test
    void statusCodesMapLikeTheChatBackend() throws Exception {
        status = 429;
        assertInstanceOf(BackendResult.RateLimited.class, call("/v1/systemone", "k", Duration.ofSeconds(5)));
        status = 401;
        body = "{\"error\": \"bad key\"}";
        assertTrue(assertInstanceOf(BackendResult.Failed.class, call("/v1/systemone", "k", Duration.ofSeconds(5))).auth());
        status = 500;
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("/v1/systemone", "k", Duration.ofSeconds(5)));
        assertFalse(f.auth());
        assertTrue(f.reason().contains("500"));
    }

    @Test
    void timeoutIsANetworkFailure() throws Exception {
        delayMillis = 2000;
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("/v1/systemone", "k", Duration.ofMillis(300)));
        assertTrue(f.reason().startsWith("network"), f.reason());
    }

    @Test
    void theKeyNeverAppearsInAReason() throws Exception {
        status = 401;
        body = "invalid key sk-verysecret-123 for this account";
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("/v1/systemone", "sk-verysecret-123", Duration.ofSeconds(5)));
        assertFalse(f.reason().contains("sk-verysecret-123"), f.reason());
    }
}
