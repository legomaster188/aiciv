package com.aiciv.llm;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
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

class HttpChatBackendTest {
    HttpServer server;
    volatile int status = 200;
    volatile String body = "{\"choices\": []}";
    volatile Map<String, String> headers = Map.of();
    volatile long delayMillis;
    volatile boolean stallBody;
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.getRequestBody().readAllBytes();
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { }
            }
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            if (stallBody) {
                // Headers and one byte of a 100-byte body, then silence: the reply never finishes.
                ex.sendResponseHeaders(200, 100);
                ex.getResponseBody().write('{');
                ex.getResponseBody().flush();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                ex.close();
                return;
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    BackendResult call(String key, Duration timeout) throws Exception {
        return new HttpChatBackend(base(), key, timeout).send(new JsonObject()).get(10, SECONDS);
    }

    @Test
    void okReplyIsParsedAndKeyIsSent() throws Exception {
        BackendResult r = call("secret", Duration.ofSeconds(5));
        assertInstanceOf(BackendResult.Ok.class, r);
        assertEquals("/v1/chat/completions", paths.get(0));
        assertEquals("Bearer secret", auth.get(0));
    }

    @Test
    void trailingSlashAndNoKey() throws Exception {
        new HttpChatBackend(base() + "/", null, Duration.ofSeconds(5)).send(new JsonObject()).get(10, SECONDS);
        assertEquals("/v1/chat/completions", paths.get(0));
        assertEquals("null", auth.get(0));
    }

    @Test
    void rateLimitReadsRetryAfter() throws Exception {
        status = 429;
        headers = Map.of("Retry-After", "7");
        BackendResult r = call("k", Duration.ofSeconds(5));
        assertEquals(new BackendResult.RateLimited(Duration.ofSeconds(7)), r);
    }

    @Test
    void http413IsTooLargeNotAFailure() throws Exception {
        status = 413;
        body = "{\"error\": {\"message\": \"Request Entity Too Large\"}}";
        BackendResult.TooLarge r = assertInstanceOf(BackendResult.TooLarge.class, call("k", Duration.ofSeconds(5)));
        assertTrue(r.reason().startsWith("request too large: HTTP 413"), r.reason());
    }

    @Test
    void groqPerRequestTokenLimitIsTooLargeEvenAs429() throws Exception {
        status = 429;
        body = "{\"error\": {\"message\": \"Request too large for model `llama-3.3-70b-versatile` in organization `org` service tier "
                + "`on_demand` on tokens per minute (TPM): Limit 6000, Requested 9512, please reduce your message size and try again.\"}}";
        assertInstanceOf(BackendResult.TooLarge.class, call("k", Duration.ofSeconds(5)));
        // An ordinary per-minute limit is still a rate limit to wait out.
        body = "{\"error\": {\"message\": \"Rate limit reached for model on tokens per minute (TPM): Limit 6000, Used 5000, Requested 2000.\"}}";
        assertInstanceOf(BackendResult.RateLimited.class, call("k", Duration.ofSeconds(5)));
    }

    @Test
    void rateLimitWithoutRetryAfter() throws Exception {
        status = 429;
        assertEquals(new BackendResult.RateLimited(null), call("k", Duration.ofSeconds(5)));
    }

    @Test
    void unauthorizedIsAnAuthFailure() throws Exception {
        status = 401;
        body = "{\"error\": \"bad key\"}";
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofSeconds(5)));
        assertTrue(f.auth());
    }

    @Test
    void badGeminiKeyIsAnAuthFailure() throws Exception {
        status = 400;
        body = "[{\"error\": {\"code\": 400, \"message\": \"API key not valid. Please pass a valid API key.\"}}]";
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofSeconds(5)));
        assertTrue(f.auth(), f.reason());
    }

    @Test
    void serverErrorIsNotAuth() throws Exception {
        status = 503;
        body = "overloaded";
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofSeconds(5)));
        assertFalse(f.auth());
        assertTrue(f.reason().contains("503"));
    }

    @Test
    void nonJsonOkIsAFailure() throws Exception {
        body = "<html>oops</html>";
        assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofSeconds(5)));
    }

    @Test
    void timeoutIsANetworkFailure() throws Exception {
        delayMillis = 2000;
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("k", Duration.ofMillis(300)));
        assertTrue(f.reason().startsWith("network"), f.reason());
    }

    @Test
    void stalledBodyIsANetworkFailure() throws Exception {
        stallBody = true;
        // Waits only 1 s: without the overall timeout the future stays pending past this bound.
        BackendResult r = new HttpChatBackend(base(), "k", Duration.ofMillis(300)).send(new JsonObject()).get(1, SECONDS);
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, r);
        assertTrue(f.reason().startsWith("network"), f.reason());
    }

    @Test
    void keyWithControlCharactersIsAnAuthFailureAndNothingIsSent() throws Exception {
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("abc\n", Duration.ofSeconds(5)));
        assertTrue(f.auth());
        assertTrue(paths.isEmpty(), "request must not be sent with an unusable key");
    }

    @Test
    void providerEchoingTheKeyIsRedacted() throws Exception {
        status = 401;
        body = "{\"error\": \"key secret-key-123 is not valid\"}";
        BackendResult.Failed f = assertInstanceOf(BackendResult.Failed.class, call("secret-key-123", Duration.ofSeconds(5)));
        assertTrue(f.auth());
        assertFalse(f.reason().contains("secret-key-123"), f.reason());
        assertTrue(f.reason().contains("***"), f.reason());
    }
}
