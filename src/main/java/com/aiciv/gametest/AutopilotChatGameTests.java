package com.aiciv.gametest;

import com.aiciv.autopilot.personality.Personalities;
import com.aiciv.autopilot.personality.Personality;
import com.aiciv.autopilot.personality.Traits;
import com.aiciv.bot.BotException;
import com.aiciv.bot.BotManager;
import com.aiciv.brain.DecisionLog;
import com.aiciv.config.AiCivConfig;
import com.aiciv.llm.LlmService;
import com.aiciv.llm.ScriptedBackend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Autopilot chat (Task 4). Bot names start with TestApChat. */
public final class AutopilotChatGameTests {
    @GameTest
    public void classLoads(GameTestHelper helper) {
        helper.succeed();
    }

    /** The shipped personalities, plus a stand-in "chatty" when this build's Personalities does not ship one yet. */
    private static Personalities withChatty() {
        List<Personality> all = new ArrayList<>(Personalities.defaults().all());
        if (all.stream().noneMatch(p -> p.id().equals("chatty"))) {
            all.add(new Personality("chatty", "Chatty", "Talks a lot.", new Traits(-0.2, -0.8, 1.0, 0, 0), Map.of(), Map.of()));
        }
        return Personalities.of(all);
    }

    private static GameTestKit.AutoSpawned spawnChatty(GameTestHelper helper, String name, double x, double z) {
        try {
            Path dir = Files.createTempDirectory("aiciv-gametest");
            AiCivConfig config = AiCivConfig.GSON.fromJson(GameTestKit.autopilotConfig(""), AiCivConfig.class);
            ScriptedBackend backend = new ScriptedBackend(List.of());
            // game tests run far faster than 20 ticks per second of wall time; chat gaps and cooldowns are in milliseconds
            var server = helper.getLevel().getServer();
            long base = System.currentTimeMillis();
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return Instant.ofEpochMilli(base + server.getTickCount() * 50L); }
            };
            LlmService llm = new LlmService(config, Map.of("scripted", backend), clock);
            BotManager manager = new BotManager(server, config, llm, dir.resolve("roster.json"),
                    new DecisionLog(dir.resolve("logs")), clock, withChatty());
            var bot = manager.spawn(name, "autopilot-chatty", "", helper.getLevel(), helper.absoluteVec(new Vec3(x, 1, z)));
            helper.onEachTick(() -> {
                manager.tickStart();
                manager.tickEnd();
            });
            return new GameTestKit.AutoSpawned(manager, bot, dir, backend);
        } catch (Exception e) {
            throw new IllegalStateException("could not spawn " + name, e);
        }
    }

    /** A logged chat line of that event and source whose text has no unfilled placeholder. */
    private static boolean chatLine(String line, String event, String source) {
        int text = line.indexOf("\"text\":");
        return line.contains("\"mode\":\"autopilot-chat\"") && line.contains("\"event\":\"" + event + "\"")
                && line.contains("\"source\":\"" + source + "\"") && text >= 0 && !line.substring(text).contains("{");
    }

    @GameTest(maxTicks = 400)
    public void chatLinesReachDecisionLog(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.AutoSpawned a = spawnChatty(helper, "TestApChatA", 2.5, 2.5);
        boolean[] said = {false};
        helper.succeedWhen(() -> {
            List<String> lines = GameTestKit.decisionLines(a.dir(), "TestApChatA");
            helper.assertTrue(lines.stream().anyMatch(l -> chatLine(l, "greet", "template")),
                    "no greeting in the log yet: " + lines);
            if (!said[0]) {
                said[0] = true;
                try {
                    a.manager().spawn("TestApChatB", "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(5.5, 1, 5.5)));
                    a.manager().say("TestApChatB", "TestApChatA how are you");
                } catch (BotException e) {
                    throw new IllegalStateException(e);
                }
            }
            helper.assertTrue(lines.stream().anyMatch(l -> chatLine(l, "mention", "template")),
                    "no mention reply in the log yet: " + lines);
            for (String bot : List.of("TestApChatB", "TestApChatA")) {
                try {
                    a.manager().remove(bot);
                } catch (BotException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }
}
