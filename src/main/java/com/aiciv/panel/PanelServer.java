package com.aiciv.panel;

import com.aiciv.AiCiv;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotException;
import com.aiciv.bot.BotManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;

/**
 * The local web admin panel: a page plus a small JSON API on 127.0.0.1. HTTP threads never touch game state; they
 * serve the snapshot the server thread publishes, and run every change as a task on the server thread.
 */
public final class PanelServer {
    /** Re-reads aiciv.json and keys.json; returns config problems. */
    @FunctionalInterface
    public interface Reloader {
        List<String> reload() throws IOException;
    }

    /** One change, run on the server thread; returns the message for the caller. */
    @FunctionalInterface
    private interface Task {
        String run() throws BotException, IOException;
    }

    /** A POST that failed validation; becomes a 400. */
    private record Rejected(String problem) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int SNAPSHOT_EVERY_TICKS = 10;
    private static final int MAX_BODY = 16 * 1024;
    private static final int WORKER_THREADS = 2;
    private static final int DEFAULT_DECISIONS = 50;
    private static final int MAX_DECISIONS = 200;
    private static final long TASK_TIMEOUT_SECONDS = 5;
    private static final String PAGE = "assets/aiciv/panel/index.html";
    /** The page is self-contained, so nothing else may load, and nothing may frame it. */
    private static final String CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            + "connect-src 'self'; img-src data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private final MinecraftServer server;
    private final BotManager manager;
    private final Reloader reloader;
    private final String token;
    private final HttpServer http;
    private final ExecutorService pool;
    private final int port;
    private final byte[] page;
    private volatile PanelSnapshot snapshot;
    private int ticks;

    private PanelServer(MinecraftServer server, BotManager manager, Reloader reloader, int requestedPort) throws IOException {
        this.server = server;
        this.manager = manager;
        this.reloader = reloader;
        this.token = PanelSecurity.newToken();
        this.page = loadPage();
        // 127.0.0.1 only: the panel can spawn and remove players, so it must never be reachable from the network.
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), requestedPort), 0);
        this.port = http.getAddress().getPort();
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(WORKER_THREADS, r -> {
            Thread t = new Thread(r, "AiCiv-Panel-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/", this::handle);
    }

    /** Starts listening. Call on the server thread; pass port 0 to take any free port. */
    public static PanelServer start(MinecraftServer server, BotManager manager, int port, Reloader reloader) throws IOException {
        PanelServer p = new PanelServer(server, manager, reloader, port);
        p.publish();
        p.http.start();
        return p;
    }

    public void stop() {
        http.stop(0);
        pool.shutdownNow();
    }

    public int port() { return port; }

    public String token() { return token; }

    public String baseUrl() {
        return "http://localhost:" + port + "/";
    }

    /** The URL that opens the panel signed in. It carries the token, so only ever show it to an operator. */
    public String linkWithToken() {
        return baseUrl() + "#t=" + token;
    }

    /** Called every server tick (server thread). */
    public void tick() {
        if (++ticks % SNAPSHOT_EVERY_TICKS == 0) publish();
    }

    private void publish() {
        snapshot = PanelSnapshot.capture(manager);
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!PanelSecurity.hostOk(ex.getRequestHeaders().getFirst("Host"), port)
                    || !PanelSecurity.originOk(ex.getRequestHeaders().getFirst("Origin"), port)) {
                reply(ex, 403, "forbidden", "text/plain");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                if (!ex.getRequestMethod().equals("GET")) reply(ex, 405, "method not allowed", "text/plain");
                else reply(ex, 200, page, "text/html; charset=utf-8");
            } else if (path.startsWith("/api/")) {
                if (!PanelSecurity.tokenOk(token, ex.getRequestHeaders().getFirst(PanelSecurity.TOKEN_HEADER))) {
                    reply(ex, 403, json(false, "missing or wrong token"), "application/json");
                } else {
                    api(ex, path);
                }
            } else {
                reply(ex, 404, "not found", "text/plain");
            }
        } catch (RuntimeException e) {
            AiCiv.LOG.warn("AiCiv panel request failed", e);
            reply(ex, 500, json(false, "internal error"), "application/json");
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        if (path.equals("/api/state") || path.equals("/api/decisions")) {
            if (!method.equals("GET")) {
                reply(ex, 405, json(false, "use GET"), "application/json");
            } else if (path.equals("/api/state")) {
                PanelSnapshot s = snapshot;
                reply(ex, s == null ? 503 : 200, s == null ? json(false, "not ready") : GSON.toJson(s), "application/json");
            } else {
                decisions(ex);
            }
            return;
        }
        if (!method.equals("POST")) {
            reply(ex, 405, json(false, "use POST"), "application/json");
            return;
        }
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            reply(ex, 413, json(false, "request body is too large"), "application/json");
            return;
        }
        JsonObject body;
        try {
            JsonElement parsed = raw.length == 0 ? new JsonObject() : JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException();
            body = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            reply(ex, 400, json(false, "the body must be a JSON object"), "application/json");
            return;
        }
        Object job = post(path.substring("/api/".length()), body);
        if (job == null) {
            reply(ex, 404, json(false, "unknown action"), "application/json");
        } else if (job instanceof Rejected r) {
            reply(ex, 400, json(false, r.problem()), "application/json");
        } else {
            runOnServer((Task) job, ex);
        }
    }

    private void decisions(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String bot = q.get("bot");
        String problem = PanelInput.checkName(bot); // also keeps path tricks out of the log file name
        if (problem != null) {
            reply(ex, 400, json(false, problem), "application/json");
            return;
        }
        int limit = DEFAULT_DECISIONS;
        try {
            if (q.containsKey("limit")) limit = Math.max(1, Math.min(MAX_DECISIONS, Integer.parseInt(q.get("limit"))));
        } catch (NumberFormatException e) {
            reply(ex, 400, json(false, "limit must be a number"), "application/json");
            return;
        }
        JsonArray list = new JsonArray();
        manager.decisionLog().tail(bot, limit).forEach(list::add);
        JsonObject out = new JsonObject();
        out.addProperty("bot", bot);
        out.add("decisions", list);
        reply(ex, 200, GSON.toJson(out), "application/json");
    }

    // ---------------------------------------------------------------- changes

    /**
     * Validates one POST on the HTTP thread. Returns a {@link Task} to run on the server thread, a {@link Rejected}
     * for a 400, or null when the action does not exist.
     */
    private Object post(String action, JsonObject body) {
        String name = PanelInput.string(body, "name");
        String bad;
        switch (action) {
            case "spawn" -> {
                String model = PanelInput.string(body, "model");
                String persona = PanelInput.string(body, "persona");
                boolean modelGiven = model != null && !model.isBlank();
                bad = first(PanelInput.checkName(name), modelGiven ? PanelInput.checkModel(model, knownModels()) : null,
                        PanelInput.checkText("persona", persona, PanelInput.MAX_PERSONA, false));
                if (bad != null) return new Rejected(bad);
                String chosenModel = modelGiven ? model : null;
                String chosenPersona = persona == null || persona.isBlank() ? null : persona.trim();
                return (Task) () -> {
                    Bot b = manager.spawnAtDefault(name, chosenModel, chosenPersona);
                    return "Spawned " + b.name + " using " + b.brain.modelKey();
                };
            }
            case "remove" -> {
                bad = PanelInput.checkName(name);
                if (bad != null) return new Rejected(bad);
                return (Task) () -> {
                    manager.remove(name);
                    return "Removed " + name;
                };
            }
            case "pause", "resume" -> {
                bad = PanelInput.checkTarget(name);
                if (bad != null) return new Rejected(bad);
                boolean pause = action.equals("pause");
                return (Task) () -> (pause ? "Paused " : "Resumed ") + manager.setPaused(name, pause) + " AI player(s)";
            }
            case "goal" -> {
                String goal = PanelInput.string(body, "goal");
                bad = first(PanelInput.checkName(name), goal == null ? "goal is required" : null,
                        PanelInput.checkText("goal", goal, PanelInput.MAX_GOAL, false));
                if (bad != null) return new Rejected(bad);
                return (Task) () -> {
                    manager.setGoal(name, goal.trim());
                    return "Goal set for " + name;
                };
            }
            case "model" -> {
                String model = PanelInput.string(body, "model");
                bad = first(PanelInput.checkName(name), PanelInput.checkModel(model, knownModels()));
                if (bad != null) return new Rejected(bad);
                return (Task) () -> {
                    manager.setModel(name, model);
                    return name + " now uses " + model;
                };
            }
            case "say" -> {
                String text = PanelInput.string(body, "text");
                bad = first(PanelInput.checkName(name), PanelInput.checkText("text", text, PanelInput.MAX_SAY, true));
                if (bad != null) return new Rejected(bad);
                return (Task) () -> {
                    manager.say(name, text.trim());
                    return name + " said it";
                };
            }
            case "reload" -> {
                return (Task) () -> {
                    List<String> problems = reloader.reload();
                    return problems.isEmpty() ? "Reloaded aiciv.json and keys.json"
                            : "Reloaded, with problems: " + String.join("; ", problems);
                };
            }
            default -> {
                return null;
            }
        }
    }

    private Set<String> knownModels() {
        PanelSnapshot s = snapshot;
        return s == null ? Set.of() : s.models().stream().map(PanelSnapshot.ModelView::key).collect(Collectors.toSet());
    }

    private void runOnServer(Task task, HttpExchange ex) throws IOException {
        CompletableFuture<String> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(task.run());
                publish(); // the page sees the change at once rather than up to half a second later
            } catch (BotException | IOException e) {
                result.completeExceptionally(e);
            } catch (RuntimeException e) {
                AiCiv.LOG.warn("AiCiv panel action failed", e);
                result.completeExceptionally(new BotException("internal error: " + e.getClass().getSimpleName()));
            }
        });
        try {
            reply(ex, 200, json(true, result.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)), "application/json");
        } catch (ExecutionException e) {
            String why = e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage() : "failed";
            reply(ex, 400, json(false, why), "application/json");
        } catch (TimeoutException e) {
            reply(ex, 504, json(false, "the server did not answer within " + TASK_TIMEOUT_SECONDS + " seconds"), "application/json");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply(ex, 503, json(false, "interrupted"), "application/json");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String first(String... problems) {
        for (String p : problems) if (p != null) return p;
        return null;
    }

    private static String json(boolean ok, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("message", message);
        return GSON.toJson(o);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static byte[] loadPage() throws IOException {
        try (InputStream in = PanelServer.class.getClassLoader().getResourceAsStream(PAGE)) {
            if (in == null) throw new IOException("missing resource " + PAGE);
            return in.readAllBytes();
        }
    }

    private static void reply(HttpExchange ex, int status, String text, String type) throws IOException {
        boolean textual = type.equals("text/plain") || type.equals("application/json");
        reply(ex, status, text.getBytes(StandardCharsets.UTF_8), textual ? type + "; charset=utf-8" : type);
    }

    private static void reply(HttpExchange ex, int status, byte[] bytes, String type) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", CSP);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
    }
}
