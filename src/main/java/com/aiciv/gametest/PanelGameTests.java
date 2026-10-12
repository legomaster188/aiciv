package com.aiciv.gametest;

import com.aiciv.bot.BotManager;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.panel.PanelSecurity;
import com.aiciv.panel.PanelServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class PanelGameTests {
    private static HttpRequest.Builder request(PanelServer panel, String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + path));
        if (token != null) b.header(PanelSecurity.TOKEN_HEADER, token);
        return b;
    }

    private static CompletableFuture<HttpResponse<String>> send(HttpClient client, HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean paused(HttpResponse<String> state, String bot) {
        for (var e : JsonParser.parseString(state.body()).getAsJsonObject().getAsJsonArray("bots")) {
            JsonObject b = e.getAsJsonObject();
            if (b.get("name").getAsString().equals(bot)) return b.get("paused").getAsBoolean();
        }
        throw new IllegalStateException(bot + " is not in " + state.body());
    }

    /** The test thread is the server thread, so every request is asynchronous and the checks run as ticks pass. */
    @GameTest(maxTicks = 300)
    public void panelServesStateAndGuardsAccess(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        BotManager manager = AiCivGameTests.scriptedManager(helper, new ScriptedBackend(List.of()), Files.createTempDirectory("aiciv-gametest"));
        manager.spawn("TestPanel", "scripted", "a panel test bot", helper.getLevel(), helper.absoluteVec(new Vec3(3.5, 1, 3.5)));
        PanelServer panel = PanelServer.start(helper.getLevel().getServer(), manager, 0, List::of);
        HttpClient client = HttpClient.newHttpClient();
        String token = panel.token();

        CompletableFuture<HttpResponse<String>> withToken = send(client, request(panel, "/api/state", token).GET().build());
        CompletableFuture<HttpResponse<String>> noToken = send(client, request(panel, "/api/state", null).GET().build());
        CompletableFuture<HttpResponse<String>> wrongToken = send(client, request(panel, "/api/state", "0".repeat(32)).GET().build());
        CompletableFuture<HttpResponse<String>> badOrigin = send(client,
                request(panel, "/api/state", token).header("Origin", "http://evil.example").GET().build());
        CompletableFuture<HttpResponse<String>> page = send(client, request(panel, "/", null).GET().build());
        CompletableFuture<HttpResponse<String>> pause = send(client, request(panel, "/api/pause", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"TestPanel\"}")).build());
        CompletableFuture<HttpResponse<String>> badSpawn = send(client, request(panel, "/api/spawn", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\",\"model\":\"nope\"}")).build());
        CompletableFuture<HttpResponse<String>> afterPause = pause.thenCompose(p -> send(client, request(panel, "/api/state", token).GET().build()));

        helper.onEachTick(() -> {
            manager.tickStart();
            panel.tick();
        });
        helper.runAfterDelay(299, panel::stop); // a failed run still releases the port
        helper.succeedWhen(() -> {
            for (var f : List.of(withToken, noToken, wrongToken, badOrigin, page, pause, badSpawn, afterPause)) {
                helper.assertTrue(f.isDone(), "a panel request is still pending");
                f.join(); // rethrows a transport failure
            }
            HttpResponse<String> ok = withToken.join();
            helper.assertTrue(ok.statusCode() == 200, "state with the token gave " + ok.statusCode());
            helper.assertTrue(ok.body().contains("\"TestPanel\"") && ok.body().contains("\"scripted\""), "state lacks the bot: " + ok.body());
            helper.assertTrue(!paused(ok, "TestPanel"), "the bot should start unpaused");
            helper.assertTrue(noToken.join().statusCode() == 403, "no token gave " + noToken.join().statusCode());
            helper.assertTrue(wrongToken.join().statusCode() == 403, "wrong token gave " + wrongToken.join().statusCode());
            helper.assertTrue(badOrigin.join().statusCode() == 403, "foreign origin gave " + badOrigin.join().statusCode());
            helper.assertTrue(page.join().statusCode() == 200 && page.join().body().contains("AiCiv"), "the page did not load");
            helper.assertTrue(!page.join().body().contains(token), "the page must not embed the token");
            helper.assertTrue(pause.join().statusCode() == 200 && pause.join().body().contains("\"ok\":true"), "pause gave " + pause.join().body());
            helper.assertTrue(badSpawn.join().statusCode() == 400, "a bad spawn gave " + badSpawn.join().statusCode());
            helper.assertTrue(paused(afterPause.join(), "TestPanel"), "state after the pause: " + afterPause.join().body());
            panel.stop();
            try {
                manager.remove("TestPanel");
            } catch (com.aiciv.bot.BotException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
