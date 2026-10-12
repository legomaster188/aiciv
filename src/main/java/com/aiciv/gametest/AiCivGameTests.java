package com.aiciv.gametest;

import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.brain.DecisionLog;
import com.aiciv.config.AiCivConfig;
import com.aiciv.llm.LlmService;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.nav.LevelNavigator;
import com.aiciv.nav.NavBudget;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotFormatter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class AiCivGameTests {
    static void stoneFloor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    @GameTest(maxTicks = 200)
    public void fakePlayerJoinsWalksAndLeaves(GameTestHelper helper) {
        stoneFloor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestWalker", () -> {});
        Vec3 start = helper.absoluteVec(new Vec3(0.5, 1, 3.5));
        // Yaw -90 faces +X (east).
        bot.moveTo(helper.getLevel(), start, -90.0F);
        helper.onEachTick(() -> {
            bot.setYRot(-90.0F);
            bot.zza = 1.0F;
        });
        helper.runAfterDelay(25, () -> {
            double moved = bot.position().x - start.x;
            server.getPlayerList().remove(bot);
            helper.assertTrue(moved > 2.0, "bot moved only " + moved + " blocks");
            helper.assertTrue(server.getPlayerList().getPlayerByName("TestWalker") == null, "bot is still in the player list");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void navigatorCrossesObstacleCourse(GameTestHelper helper) {
        stoneFloor(helper);
        // Raised ground from x = 5: the bot must jump up one block.
        for (int x = 5; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 1, z, Blocks.STONE);
        // Magma across the straight line: the bot must walk around it.
        for (int z = 0; z <= 2; z++) helper.setBlock(3, 0, z, Blocks.MAGMA_BLOCK);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestNavigator", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(0.5, 1, 1.5)), 0.0F);
        NavBudget budget = new NavBudget(NavBudget.DEFAULT_NANOS, System::nanoTime);
        LevelNavigator nav = new LevelNavigator(bot, budget);
        BlockPos goal = helper.absolutePos(new BlockPos(6, 2, 1));
        nav.goTo(new NearGoal(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.5));
        boolean[] crossedMagma = {false};
        helper.onEachTick(() -> {
            budget.tick();
            nav.tick();
            if (helper.getLevel().getBlockState(bot.blockPosition().below()).is(Blocks.MAGMA_BLOCK)) crossedMagma[0] = true;
            if (crossedMagma[0] || nav.status().state() == NavStatus.State.FAILED) {
                server.getPlayerList().remove(bot);
                helper.fail(crossedMagma[0] ? "bot stepped on magma" : "navigator failed: " + nav.status().reason());
            }
        });
        helper.succeedWhen(() -> {
            NavStatus s = nav.status();
            helper.assertTrue(s.state() == NavStatus.State.ARRIVED, "navigator is " + s.state() + " " + s.reason());
            helper.assertTrue(bot.getY() > goal.getY() - 0.1, "bot is below the raised ground at y " + bot.getY());
            server.getPlayerList().remove(bot);
        });
    }

    @GameTest(maxTicks = 100)
    public void perceiverSeesExposedBlocksOnly(GameTestHelper helper) {
        stoneFloor(helper);
        helper.setBlock(3, 1, 3, Blocks.CRAFTING_TABLE);
        // A diamond ore with stone on all six sides: it must stay invisible.
        for (int x = 5; x <= 7; x++) for (int y = 1; y <= 3; y++) for (int z = 5; z <= 7; z++) helper.setBlock(x, y, z, Blocks.STONE);
        helper.setBlock(6, 2, 6, Blocks.DIAMOND_ORE);
        // A lava source sealed in stone: fluids must not be seen through walls either.
        for (int x = 1; x <= 3; x++) for (int y = 1; y <= 3; y++) for (int z = 5; z <= 7; z++) helper.setBlock(x, y, z, Blocks.STONE);
        helper.setBlock(2, 2, 6, Blocks.LAVA);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestPerceiver", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)), 0.0F);
        helper.runAfterDelay(5, () -> {
            String text = SnapshotFormatter.format(
                    Perceiver.capture(bot, "none", "nothing yet", p -> p instanceof AiPlayer), SnapshotFormatter.MAX_CHARS);
            server.getPlayerList().remove(bot);
            helper.assertTrue(text.startsWith("You: TestPerceiver"), text);
            helper.assertTrue(text.contains("crafting_table"), "snapshot misses the crafting table:\n" + text);
            helper.assertTrue(!text.contains("diamond_ore"), "snapshot shows a buried ore:\n" + text);
            helper.assertTrue(!text.contains("lava"), "snapshot shows enclosed lava:\n" + text);
            helper.succeed();
        });
    }

    static AiCivConfig scriptedConfig() {
        return AiCivConfig.GSON.fromJson("""
                {"providers": {"scripted": {"type": "scripted", "script": "unused.json"}},
                 "models": {"scripted": {"provider": "scripted", "model": "script"}},
                 "defaultModel": "scripted"}""", AiCivConfig.class);
    }

    static BotManager scriptedManager(GameTestHelper helper, ScriptedBackend backend, Path dir) throws Exception {
        AiCivConfig config = scriptedConfig();
        LlmService llm = new LlmService(config, Map.of("scripted", backend), Clock.systemUTC());
        return new BotManager(helper.getLevel().getServer(), config, llm,
                dir.resolve("roster.json"), new DecisionLog(dir.resolve("logs")), Clock.systemUTC());
    }

    @GameTest(maxTicks = 400)
    public void scriptedBotSaysHelloAndWalks(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        BlockPos target = helper.absolutePos(new BlockPos(6, 1, 6));
        JsonObject turn = JsonParser.parseString("""
                {"content": "Let me look around.", "tool_calls": [
                  {"id": "1", "type": "function", "function": {"name": "say", "arguments": "{\\"text\\": \\"hello world\\"}"}},
                  {"id": "2", "type": "function", "function": {"name": "goTo", "arguments": "{\\"x\\": %d, \\"y\\": %d, \\"z\\": %d}"}}]}"""
                .formatted(target.getX(), target.getY(), target.getZ())).getAsJsonObject();
        ScriptedBackend backend = new ScriptedBackend(List.of(turn));
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = scriptedManager(helper, backend, dir);
        Bot bot = manager.spawn("TestScripted", "scripted", "a test bot", helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        helper.onEachTick(() -> {
            manager.tickStart();
            manager.tickEnd();
        });
        helper.succeedWhen(() -> {
            // goTo arrives within 1.5 blocks of the target cell's centre, and the bot may stand anywhere in its cell.
            helper.assertTrue(bot.player.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 2.2,
                    "bot is at " + bot.player.position() + ", target " + target);
            helper.assertTrue(backend.requests().size() >= 1, "no LLM request was made");
            String system = backend.requests().get(0).getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            helper.assertTrue(system.contains("You are TestScripted") && system.contains("a test bot"), system);
            String log;
            try {
                log = Files.readString(dir.resolve("logs").resolve("TestScripted.jsonl"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            helper.assertTrue(log.contains("\"goTo\"") && log.contains("Let me look around."), log);
            try {
                manager.remove("TestScripted");
            } catch (com.aiciv.bot.BotException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void botDeathLogsOutWithoutMoreCalls(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        ScriptedBackend backend = new ScriptedBackend(List.of());
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = scriptedManager(helper, backend, dir);
        Bot bot = manager.spawn("TestDies", "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        helper.onEachTick(() -> {
            manager.tickStart();
            manager.tickEnd();
        });
        int[] requestsAfterDeath = new int[1];
        helper.runAfterDelay(5, () -> {
            bot.player.kill(helper.getLevel());
            // The global AiCiv listener only feeds the global manager, so call ours directly.
            manager.onDeath(bot.player);
            requestsAfterDeath[0] = backend.requests().size();
            helper.assertTrue(bot.player.getHealth() > 0, "health was not restored");
        });
        helper.runAfterDelay(45, () -> {
            helper.assertTrue(manager.bot("TestDies").isEmpty(), "dead bot is still managed");
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayerByName("TestDies") == null, "dead bot is still in the player list");
            helper.assertTrue(backend.requests().size() == requestsAfterDeath[0], "LLM was called after death");
            helper.assertTrue(manager.listLines().get(0).startsWith("TestDies [scripted] offline"), manager.listLines().get(0));
            Bot back;
            try {
                back = manager.spawn("TestDies", null, null, helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
            } catch (com.aiciv.bot.BotException e) {
                throw new IllegalStateException(e);
            }
            helper.assertTrue(manager.bot("TestDies").isPresent(), "respawned bot is not managed");
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayerByName("TestDies") == back.player, "respawned bot is not in the player list");
            helper.assertTrue(!back.player.isDeadOrDying(), "respawned bot is dead");
            helper.assertTrue("scripted".equals(back.brain.modelKey()), "respawned bot has model " + back.brain.modelKey());
            try {
                manager.remove("TestDies");
            } catch (com.aiciv.bot.BotException e) {
                throw new IllegalStateException(e);
            }
            helper.succeed();
        });
    }
}
