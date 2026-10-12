package com.aiciv.gametest;

import com.aiciv.actions.CollectAction;
import com.aiciv.actions.ExploreAction;
import com.aiciv.actions.MineAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.bot.FakeConnection;
import com.aiciv.brain.DecisionLog;
import com.aiciv.config.AiCivConfig;
import com.aiciv.llm.LlmService;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.nav.LevelNavigator;
import com.aiciv.nav.NavBudget;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotFormatter;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockNames;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.KnownBlocks;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
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

    @GameTest(maxTicks = 100)
    public void botChangingDimensionStaysVulnerable(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the test server has no nether");
        AiPlayer bot = AiPlayer.join(server, "TestDimension", () -> {});
        bot.moveTo(nether, new Vec3(0.5, 100, 0.5), 0.0F);
        helper.runAfterDelay(3, () -> {
            boolean changing = bot.isChangingDimension();
            boolean inNether = bot.level() == nether;
            server.getPlayerList().remove(bot);
            helper.assertTrue(inNether, "bot did not reach the nether");
            helper.assertTrue(!changing, "bot is still flagged as changing dimension, so it is invulnerable");
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
        LevelNavigator nav = new LevelNavigator(bot, budget, new BlockBreaker(bot));
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

    @GameTest(maxTicks = 100)
    public void perceiverRemembersAndForgetsBlocksAndShowsGear(GameTestHelper helper) {
        stoneFloor(helper);
        helper.setBlock(3, 1, 3, Blocks.GOLD_ORE);
        helper.setBlock(1, 1, 6, Blocks.DEEPSLATE_GOLD_ORE);
        helper.setBlock(5, 1, 5, Blocks.OAK_LOG);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestRemember", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)), 0.0F);
        bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        KnownBlocks known = new KnownBlocks(128);
        BlockPos ore = helper.absolutePos(new BlockPos(3, 1, 3));
        Pos orePos = new Pos(ore.getX(), ore.getY(), ore.getZ());
        Pos far = new Pos(ore.getX() + 200, 40, ore.getZ());
        known.see("diamond_ore", far, 0);
        helper.runAfterDelay(5, () -> {
            String first = SnapshotFormatter.format(
                    Perceiver.capture(bot, "none", "nothing yet", p -> p instanceof AiPlayer, known), SnapshotFormatter.MAX_CHARS);
            boolean cachedOre = known.nearest(l -> l.equals("gold_ore"), orePos).equals(java.util.Optional.of(orePos));
            boolean cachedDeepslate = known.nearest(l -> l.equals("deepslate_gold_ore"), orePos).isPresent();
            boolean cachedLog = known.nearest(l -> l.equals("oak_log"), orePos).isPresent();
            List<Map.Entry<Pos, String>> goldNearby = Perceiver.exposedBlocks(helper.getLevel(), bot.blockPosition(), 8,
                    BlockNames.matcher("gold"));
            // Mine the ore away: the next snapshot must forget it, while the far diamond stays remembered.
            helper.getLevel().setBlock(ore, Blocks.AIR.defaultBlockState(), 3);
            SnapshotData secondData = Perceiver.capture(bot, "none", "nothing yet", p -> p instanceof AiPlayer, known);
            String second = SnapshotFormatter.format(secondData, SnapshotFormatter.MAX_CHARS);
            boolean forgotOre = known.nearest(l -> l.equals("gold_ore"), orePos).isEmpty();
            boolean keptFar = known.nearest(l -> l.equals("diamond_ore"), orePos).isPresent();
            server.getPlayerList().remove(bot);
            helper.assertTrue(first.contains("Wearing: iron_chestplate, shield"), first);
            helper.assertTrue(first.contains("Remembered: diamond_ore at " + far.x() + " 40 " + far.z()), first);
            helper.assertTrue(cachedOre && cachedDeepslate && cachedLog, "scan did not cache the ore, deepslate ore and log:\n" + first);
            helper.assertTrue(goldNearby.size() == 2 && goldNearby.get(0).getValue().equals("gold_ore"),
                    "exposedBlocks should list the plain gold ore first, then the deepslate one: " + goldNearby);
            helper.assertTrue(forgotOre, "the mined ore is still remembered");
            helper.assertTrue(keptFar, "a far-away entry outside the scan was forgotten");
            helper.assertTrue(secondData.blocks().stream().noneMatch(b -> b.label().equals("gold_ore")), second);
            helper.assertTrue(second.contains("Remembered: diamond_ore"), second);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void perceiverOnlyCountsBlocksTheBotCanSee(GameTestHelper helper) {
        // A rock mass with a 1x1x2 room for the bot and a tunnel running south from it.
        stoneFloor(helper);
        for (int x = 0; x < 8; x++) for (int y = 1; y <= 3; y++) for (int z = 0; z < 8; z++) helper.setBlock(x, y, z, Blocks.STONE);
        for (int z = 0; z <= 3; z++) for (int y = 1; y <= 2; y++) helper.setBlock(0, y, z, Blocks.AIR);
        // Hidden: touches a sealed air pocket, but three stone blocks stand between it and the bot.
        helper.setBlock(5, 2, 0, Blocks.AIR);
        helper.setBlock(4, 2, 0, Blocks.EMERALD_ORE);
        // Visible: at the end of the tunnel, straight down the line of sight.
        helper.setBlock(0, 2, 4, Blocks.LAPIS_ORE);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestSight", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(0.5, 1, 0.5)), 0.0F);
        KnownBlocks known = new KnownBlocks(128);
        helper.runAfterDelay(5, () -> {
            SnapshotData data = Perceiver.capture(bot, "none", "nothing yet", p -> p instanceof AiPlayer, known);
            List<Map.Entry<Pos, String>> seen = Perceiver.exposedBlocks(helper.getLevel(), bot.blockPosition(), 8,
                    BlockNames.matcher("ore"));
            Pos here = new Pos(bot.blockPosition().getX(), bot.blockPosition().getY(), bot.blockPosition().getZ());
            boolean cachedHidden = known.nearest(l -> l.equals("emerald_ore"), here).isPresent();
            boolean cachedVisible = known.nearest(l -> l.equals("lapis_ore"), here).isPresent();
            server.getPlayerList().remove(bot);
            helper.assertTrue(data.blocks().stream().noneMatch(b -> b.label().equals("emerald_ore")),
                    "snapshot lists an ore behind solid rock: " + data.blocks());
            helper.assertTrue(data.blocks().stream().anyMatch(b -> b.label().equals("lapis_ore")),
                    "snapshot misses the ore at the end of the tunnel: " + data.blocks());
            helper.assertTrue(!cachedHidden, "the hidden ore was remembered");
            helper.assertTrue(cachedVisible, "the visible ore was not remembered");
            helper.assertTrue(seen.size() == 1 && seen.get(0).getValue().equals("lapis_ore"),
                    "exposedBlocks should find only the visible ore: " + seen);
            helper.succeed();
        });
    }

    static AiCivConfig scriptedConfig() {
        return AiCivConfig.GSON.fromJson("""
                {"providers": {"scripted": {"type": "scripted", "script": "unused.json"}},
                 "models": {"scripted": {"provider": "scripted", "model": "script"}},
                 "defaultModel": "scripted", "decisionGapSeconds": 0}""", AiCivConfig.class);
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
    public void botRespawnsAfterDeath(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        ScriptedBackend backend = new ScriptedBackend(List.of());
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = scriptedManager(helper, backend, dir);
        Vec3 spot = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        Bot bot = manager.spawn("TestDies", "scripted", "", helper.getLevel(), spot);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer old = bot.player;
        boolean[] breaking = {false};
        int[] requestsAfterDeath = {-1};
        int[] requestsWhileDead = {-1};
        helper.onEachTick(() -> {
            if (requestsAfterDeath[0] >= 0 && bot.player == old) requestsWhileDead[0] = backend.requests().size();
            manager.tickStart();
            manager.tickEnd();
            if (requestsAfterDeath[0] >= 0 && bot.player == old) requestsWhileDead[0] = backend.requests().size();
            if (breaking[0]) bot.context.breaker().tick();
        });
        helper.runAfterDelay(5, () -> {
            // kill() is ignored during the 60 ticks of spawn protection, so die directly.
            old.setHealth(0.0F);
            old.die(helper.getLevel().damageSources().genericKill());
            // The global AiCiv listener only feeds the global manager, so call ours directly.
            manager.onDeath(old, helper.getLevel().damageSources().genericKill());
            manager.onDeath(old, helper.getLevel().damageSources().genericKill()); // a repeat must not respawn twice
            requestsAfterDeath[0] = backend.requests().size();
        });
        helper.runAfterDelay(15, () -> {
            AiPlayer now = bot.player;
            helper.assertTrue(now != old, "the bot still holds the dead player: health " + old.getHealth() + " removed " + old.isRemoved() + " dead " + old.isDeadOrDying());
            helper.assertTrue(old.isRemoved(), "the old player was not removed");
            helper.assertTrue(!now.isDeadOrDying() && now.getHealth() == now.getMaxHealth(), "respawned bot health " + now.getHealth());
            helper.assertTrue(now.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "respawned bot is in " + now.gameMode.getGameModeForPlayer());
            long listed = server.getPlayerList().getPlayers().stream().filter(p -> p.getScoreboardName().equals("TestDies")).count();
            helper.assertTrue(listed == 1, listed + " player list entries named TestDies");
            helper.assertTrue(server.getPlayerList().getPlayerByName("TestDies") == now, "player list does not hold the new bot");
            helper.assertTrue(now.connection.player == now, "the connection still points at the old player");
            helper.assertTrue(events(bot).contains("you died:") && events(bot).contains("Your items dropped at"), events(bot));
            helper.assertTrue(requestsWhileDead[0] == requestsAfterDeath[0], "LLM was called between death and respawn");
            helper.assertTrue(manager.bot("TestDies").get() == bot, "bot is no longer managed");
            helper.assertTrue(bot.context.player() == now, "the context still holds the old player");
            // The replacement must work with the rebuilt navigator and breaker.
            now.moveTo(helper.getLevel(), spot, -90.0F);
            helper.setBlock(5, 1, 1, Blocks.DIRT);
            bot.navigator.goTo(new NearGoal(helper.absoluteVec(new Vec3(3.5, 1, 1.5)).x, spot.y, spot.z, 1.0));
            bot.context.breaker().start(helper.absolutePos(new BlockPos(5, 1, 1)));
            breaking[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.player != old, "not respawned yet");
            helper.assertTrue(bot.player.getX() > spot.x + 0.5, "the new player did not walk; x " + bot.player.getX());
            helper.assertTrue(helper.getBlockState(new BlockPos(5, 1, 1)).isAir(), "the new breaker did not break the dirt");
            removeBot(manager, "TestDies");
        });
        helper.runAfterDelay(195, () -> {
            if (manager.bot("TestDies").isPresent()) removeBot(manager, "TestDies");
        });
    }

    @GameTest(maxTicks = 100)
    public void reloadMovesBotsOffRemovedModelsAndPushesTiming(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = scriptedManager(helper, new ScriptedBackend(List.of()), dir);
        Bot bot = manager.spawn("TestReload", "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        AiCivConfig next = AiCivConfig.GSON.fromJson("""
                {"providers": {"scripted": {"type": "scripted", "script": "unused.json"}},
                 "models": {"renamed": {"provider": "scripted", "model": "script"}},
                 "defaultModel": "renamed", "eventWindow": 2}""", AiCivConfig.class);
        manager.reload(next, new LlmService(next, Map.of("scripted", new ScriptedBackend(List.of())), Clock.systemUTC()));
        helper.assertTrue("renamed".equals(bot.brain.modelKey()), "bot kept model " + bot.brain.modelKey());
        for (int i = 0; i < 4; i++) bot.brain.note("s", "event " + i);
        helper.assertTrue(bot.brain.events().lines().size() == 2, "event window was not applied: " + bot.brain.events().lines());
        String roster = Files.readString(dir.resolve("roster.json"));
        helper.assertTrue(roster.contains("renamed"), roster);
        manager.remove("TestReload");
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void botWithADeadProviderWaitsWithoutCrashing(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        Path dir = Files.createTempDirectory("aiciv-gametest");
        AiCivConfig config = scriptedConfig();
        // No backend for the provider: it starts disabled, so the bot has no route and no operator is online.
        LlmService llm = new LlmService(config, Map.of(), Clock.systemUTC());
        BotManager manager = new BotManager(helper.getLevel().getServer(), config, llm,
                dir.resolve("roster.json"), new DecisionLog(dir.resolve("logs")), Clock.systemUTC());
        manager.spawn("TestNoRoute", "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        helper.onEachTick(() -> {
            manager.tickStart();
            manager.tickEnd();
        });
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(manager.listLines().get(0).contains("TestNoRoute"), manager.listLines().get(0));
            try {
                manager.remove("TestNoRoute");
            } catch (com.aiciv.bot.BotException e) {
                throw new IllegalStateException(e);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void botsAreAlwaysSurvival(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = scriptedManager(helper, new ScriptedBackend(List.of()), dir);
        MinecraftServer server = helper.getLevel().getServer();
        server.setDefaultGameType(GameType.CREATIVE);
        try {
            Bot bot = manager.spawn("TestSurvival", "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
            helper.assertTrue(bot.player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                    "bot is in " + bot.player.gameMode.getGameModeForPlayer());
            manager.remove("TestSurvival");
        } finally {
            server.setDefaultGameType(GameType.SURVIVAL);
        }
        helper.succeed();
    }

    /** Removes the bots if the test times out before it cleans up itself. */
    private static void removeOnTimeout(GameTestHelper helper, int maxTicks, AiPlayer... bots) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.runAfterDelay(maxTicks - 1, () -> {
            for (AiPlayer bot : bots) if (server.getPlayerList().getPlayers().contains(bot)) server.getPlayerList().remove(bot);
        });
    }

    /** A second fake player near the work site that records what the server sends it, to see crack animations. */
    private static AiPlayer observer(GameTestHelper helper, String name, List<Packet<?>> seen) {
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer o = AiPlayer.join(server, name, () -> {});
        o.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(5.5, 1, 5.5)), 0.0F);
        FakeConnection connection = new FakeConnection();
        CommonListenerCookie cookie = new CommonListenerCookie(o.getGameProfile(), 0, o.clientInformation(), false);
        new ServerGamePacketListenerImpl(server, connection, o, cookie) {
            @Override public void send(Packet<?> packet) { seen.add(packet); }
        };
        return o;
    }

    private static int lastCrackStage(List<Packet<?>> seen, BlockPos pos) {
        int stage = -2;
        for (Packet<?> p : seen) {
            if (p instanceof ClientboundBlockDestructionPacket c && c.getPos().equals(pos)) stage = c.getProgress();
        }
        return stage;
    }

    /** Breaks the stone at (3,1,3) and checks it took between minBreakTicks and maxBreakTicks breaker ticks. */
    private static void breakStone(GameTestHelper helper, String name, boolean withPickaxe, boolean expectDrop,
                                   int minBreakTicks, int maxBreakTicks, int testTicks) {
        stoneFloor(helper);
        helper.setBlock(3, 1, 3, Blocks.STONE);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, name, () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 3.5)), -90.0F);
        List<Packet<?>> seen = new ArrayList<>();
        AiPlayer watcher = observer(helper, name + "W", seen);
        removeOnTimeout(helper, testTicks, bot, watcher);
        if (withPickaxe) bot.getInventory().setItem(15, new ItemStack(Items.WOODEN_PICKAXE));
        BlockPos stone = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockBreaker breaker = new BlockBreaker(bot);
        breaker.start(stone);
        int[] breakTicks = {0};
        int[] settled = {0};
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (breaker.busy()) {
                breakTicks[0]++;
                BlockBreaker.Status status = breaker.tick();
                if (status.state() == BlockBreaker.Status.State.FAILED) {
                    server.getPlayerList().remove(bot);
                    server.getPlayerList().remove(watcher);
                    helper.fail("breaker failed: " + status.reason());
                }
                done[0] = status.state() == BlockBreaker.Status.State.DONE;
            } else if (done[0]) {
                settled[0]++;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(done[0] && settled[0] >= 30, "block not broken and settled yet");
            helper.assertTrue(breakTicks[0] >= minBreakTicks && breakTicks[0] <= maxBreakTicks,
                    "breaking took " + breakTicks[0] + " ticks, expected " + minBreakTicks + ".." + maxBreakTicks);
            helper.assertTrue(helper.getLevel().getBlockState(stone).isAir(), "stone is still there");
            boolean cobble = InventoryOps.has(bot, Items.COBBLESTONE, 1)
                    || !helper.getLevel().getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(6),
                            e -> e.getItem().is(Items.COBBLESTONE)).isEmpty();
            helper.assertTrue(cobble == expectDrop, "cobblestone present = " + cobble + ", expected " + expectDrop);
            if (withPickaxe) {
                helper.assertTrue(bot.getInventory().getItem(bot.getInventory().getSelectedSlot()).is(Items.WOODEN_PICKAXE),
                        "pickaxe was not selected");
            }
            helper.assertTrue(lastCrackStage(seen, stone) == -1, "crack stage after DONE is " + lastCrackStage(seen, stone));
            server.getPlayerList().remove(bot);
            server.getPlayerList().remove(watcher);
        });
    }

    @GameTest(maxTicks = 200)
    public void breakerBreaksStoneWithPickaxeAndDropsCobblestone(GameTestHelper helper) {
        breakStone(helper, "TestBreakPick", true, true, 22, 26, 200);
    }

    @GameTest(maxTicks = 300)
    public void breakerWithoutToolBreaksStoneButGetsNoDrop(GameTestHelper helper) {
        breakStone(helper, "TestBreakHand", false, false, 147, 155, 300);
    }

    @GameTest(maxTicks = 100)
    public void breakerRefusesBedrockAndClearsTheCrackOnCancel(GameTestHelper helper) {
        stoneFloor(helper);
        helper.setBlock(3, 1, 3, Blocks.BEDROCK);
        helper.setBlock(4, 1, 3, Blocks.STONE);
        helper.setBlock(5, 1, 3, Blocks.STONE);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestBreakFail", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 3.5)), -90.0F);
        List<Packet<?>> seen = new ArrayList<>();
        AiPlayer watcher = observer(helper, "TestBreakFailW", seen);
        removeOnTimeout(helper, 100, bot, watcher);
        helper.runAfterDelay(3, () -> {
            try {
                BlockPos bedrock = helper.absolutePos(new BlockPos(3, 1, 3));
                BlockBreaker breaker = new BlockBreaker(bot);
                breaker.start(bedrock);
                BlockBreaker.Status status = breaker.tick();
                helper.assertTrue(status.state() == BlockBreaker.Status.State.FAILED && status.reason().equals("can't break bedrock"),
                        "bedrock gave " + status);
                helper.assertTrue(!breaker.busy(), "breaker is still busy after failing");

                BlockPos stone = helper.absolutePos(new BlockPos(4, 1, 3));
                breaker.start(stone);
                for (int i = 0; i < 10; i++) breaker.tick();
                helper.assertTrue(lastCrackStage(seen, stone) >= 0, "no crack was shown while breaking");
                breaker.cancel();
                helper.assertTrue(!breaker.busy(), "breaker is still busy after cancel");
                helper.assertTrue(lastCrackStage(seen, stone) == -1, "crack stage after cancel is " + lastCrackStage(seen, stone));

                BlockPos other = helper.absolutePos(new BlockPos(5, 1, 3));
                breaker.start(other);
                breaker.tick();
                helper.getLevel().setBlockAndUpdate(other, Blocks.DIRT.defaultBlockState());
                status = breaker.tick();
                helper.assertTrue(status.state() == BlockBreaker.Status.State.FAILED && status.reason().equals("block changed"),
                        "swapped block gave " + status);
            } finally {
                server.getPlayerList().remove(bot);
                server.getPlayerList().remove(watcher);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void placerPlacesDirtOnTheFloor(GameTestHelper helper) {
        stoneFloor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestPlacer", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 3.5)), 0.0F);
        bot.getInventory().setItem(20, new ItemStack(Items.DIRT, 3));
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.runAfterDelay(3, () -> {
            BlockPlacer.PlaceResult placed = BlockPlacer.place(bot, target, s -> s.is(Items.DIRT));
            BlockPlacer.PlaceResult again = BlockPlacer.place(bot, target, s -> s.is(Items.DIRT));
            boolean dirt = helper.getLevel().getBlockState(target).is(Blocks.DIRT);
            int left = InventoryOps.count(bot, s -> s.is(Items.DIRT));
            server.getPlayerList().remove(bot);
            helper.assertTrue(placed == BlockPlacer.PlaceResult.PLACED, "first place gave " + placed);
            helper.assertTrue(dirt, "no dirt at the target");
            helper.assertTrue(left == 2, "bot has " + left + " dirt left");
            helper.assertTrue(again == BlockPlacer.PlaceResult.OCCUPIED, "second place gave " + again);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void placerPlacesNextToACraftingTableWithoutOpeningIt(GameTestHelper helper) {
        stoneFloor(helper);
        helper.setBlock(3, 2, 2, Blocks.CRAFTING_TABLE);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestPlaceTable", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 3.5)), 0.0F);
        bot.getInventory().setItem(20, new ItemStack(Items.DIRT, 3));
        removeOnTimeout(helper, 100, bot);
        // Air below and on three sides: the table is the only thing to click against.
        BlockPos target = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.runAfterDelay(3, () -> {
            try {
                BlockPlacer.PlaceResult result = BlockPlacer.place(bot, target, s -> s.is(Items.DIRT));
                helper.assertTrue(result == BlockPlacer.PlaceResult.PLACED, "place gave " + result);
                helper.assertTrue(helper.getLevel().getBlockState(target).is(Blocks.DIRT), "no dirt at the target");
                helper.assertTrue(bot.containerMenu == bot.inventoryMenu, "the crafting table was opened");
                helper.assertTrue(!bot.isShiftKeyDown(), "bot was left sneaking");
            } finally {
                server.getPlayerList().remove(bot);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void addOrDropNeverLosesItems(GameTestHelper helper) {
        stoneFloor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestFull", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 3.5)), 0.0F);
        for (int i = 0; i < 36; i++) bot.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        helper.runAfterDelay(3, () -> {
            InventoryOps.addOrDrop(bot, new ItemStack(Items.DIRT, 5));
            int dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(3),
                    e -> e.getItem().is(Items.DIRT)).stream().mapToInt(e -> e.getItem().getCount()).sum();
            server.getPlayerList().remove(bot);
            helper.assertTrue(dropped == 5, "dropped dirt count " + dropped);
            helper.succeed();
        });
    }

    /** Walks a bot from (1,1,3) to the goal cell with the navigator; {@code check} runs once it has arrived. */
    private static void navigate(GameTestHelper helper, String name, ItemStack held, int slot, BlockPos goalCell,
                                 java.util.function.Consumer<AiPlayer> check) {
        navigate(helper, name, new Vec3(1.5, 1, 3.5), held, slot, goalCell, check);
    }

    private static void navigate(GameTestHelper helper, String name, Vec3 start, ItemStack held, int slot, BlockPos goalCell,
                                 java.util.function.Consumer<AiPlayer> check) {
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, name, () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(start), -90.0F);
        removeOnTimeout(helper, 580, bot);
        if (held != null) bot.getInventory().setItem(slot, held);
        NavBudget budget = new NavBudget(NavBudget.DEFAULT_NANOS, System::nanoTime);
        LevelNavigator nav = new LevelNavigator(bot, budget, new BlockBreaker(bot));
        BlockPos goal = helper.absolutePos(goalCell);
        nav.goTo(new NearGoal(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.5));
        helper.onEachTick(() -> {
            budget.tick();
            nav.tick();
            if (nav.status().state() == NavStatus.State.FAILED) {
                server.getPlayerList().remove(bot);
                helper.fail("navigator failed: " + nav.status().reason());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(nav.status().state() == NavStatus.State.ARRIVED, "navigator is " + nav.status().state());
            check.accept(bot);
            server.getPlayerList().remove(bot);
        });
    }

    @GameTest(maxTicks = 600)
    public void tunnelsThroughStoneWall(GameTestHelper helper) {
        stoneFloor(helper);
        for (int z = 0; z < 8; z++) for (int y = 1; y <= 2; y++) helper.setBlock(4, y, z, Blocks.STONE);
        navigate(helper, "TestTunnel", new ItemStack(Items.WOODEN_PICKAXE), 15, new BlockPos(6, 1, 3), bot -> {
            // Clearing the top block and hopping over the lower one is cheaper than a full tunnel, and still digging.
            int open = 0;
            for (int z = 0; z < 8; z++) if (helper.getBlockState(new BlockPos(4, 2, z)).isAir()) open++;
            helper.assertTrue(open >= 1, "the bot dug nothing out of the wall");
            helper.assertTrue(bot.getX() > helper.absolutePos(new BlockPos(5, 1, 3)).getX(), "bot is still west of the wall");
        });
    }

    @GameTest(maxTicks = 600)
    public void bridgesGapWithDirt(GameTestHelper helper) {
        stoneFloor(helper);
        for (int z = 0; z < 8; z++) for (int y = -2; y <= 0; y++) helper.setBlock(4, y, z, Blocks.AIR);
        navigate(helper, "TestBridge", new ItemStack(Items.DIRT, 4), 20, new BlockPos(6, 1, 3), bot -> {
            helper.assertTrue(bot.getX() > helper.absolutePos(new BlockPos(5, 1, 3)).getX(), "bot is still west of the trench");
            int dirt = InventoryOps.count(bot, s -> s.is(Items.DIRT));
            helper.assertTrue(dirt <= 3, "bot still has " + dirt + " dirt");
        });
    }

    @GameTest(maxTicks = 600)
    public void pillarsOntoLedge(GameTestHelper helper) {
        stoneFloor(helper);
        for (int x = 5; x < 8; x++) for (int z = 0; z < 8; z++) for (int y = 1; y <= 2; y++) helper.setBlock(x, y, z, Blocks.STONE);
        navigate(helper, "TestPillar", new ItemStack(Items.DIRT, 4), 20, new BlockPos(6, 3, 3), bot -> {
            helper.assertTrue(bot.getY() > helper.absolutePos(new BlockPos(6, 2, 3)).getY() + 0.9, "bot is below the ledge at y " + bot.getY());
            int dirt = InventoryOps.count(bot, s -> s.is(Items.DIRT));
            helper.assertTrue(dirt <= 3, "bot still has " + dirt + " dirt");
        });
    }

    @GameTest(maxTicks = 600)
    public void swimsAcrossPool(GameTestHelper helper) {
        stoneFloor(helper);
        for (int x = 3; x <= 5; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(x, -3, z, Blocks.STONE);
                for (int y = -2; y <= 0; y++) helper.setBlock(x, y, z, Blocks.WATER);
            }
        }
        navigate(helper, "TestSwim", null, 0, new BlockPos(6, 1, 3), bot ->
                helper.assertTrue(bot.getX() > helper.absolutePos(new BlockPos(5, 1, 3)).getX(), "bot is still west of the pool"));
    }

    @GameTest(maxTicks = 600)
    public void pillarsFromOffCentre(GameTestHelper helper) {
        stoneFloor(helper);
        for (int x = 2; x < 8; x++) for (int z = 0; z < 8; z++) for (int y = 1; y <= 2; y++) helper.setBlock(x, y, z, Blocks.STONE);
        navigate(helper, "TestPillarOff", new Vec3(1.1, 1, 3.5), new ItemStack(Items.DIRT, 4), 20, new BlockPos(3, 3, 3), bot ->
                helper.assertTrue(bot.getY() > helper.absolutePos(new BlockPos(3, 2, 3)).getY() + 0.9, "bot is below the ledge at y " + bot.getY()));
    }

    @GameTest(maxTicks = 100)
    public void followerIsStuckWhenTheBreakerFails(GameTestHelper helper) {
        stoneFloor(helper);
        helper.setBlock(2, 1, 3, Blocks.BEDROCK);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestStuckDig", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 1, 3.5)), -90.0F);
        helper.runAfterDelay(3, () -> {
            try {
                BlockPos rock = helper.absolutePos(new BlockPos(2, 1, 3));
                com.aiciv.nav.Pos at = new com.aiciv.nav.Pos(rock.getX(), rock.getY(), rock.getZ());
                com.aiciv.nav.PathStep step = new com.aiciv.nav.PathStep(at, com.aiciv.nav.Move.WALK, List.of(at), null);
                com.aiciv.nav.PathFollower follower = new com.aiciv.nav.PathFollower(List.of(step), new BlockBreaker(bot));
                com.aiciv.nav.PathFollower.Step result = follower.tick(bot);
                helper.assertTrue(result == com.aiciv.nav.PathFollower.Step.STUCK, "follower gave " + result);
            } finally {
                server.getPlayerList().remove(bot);
            }
            helper.succeed();
        });
    }

    static Bot actionBot(GameTestHelper helper, BotManager[] manager, String name, Vec3 start) throws Exception {
        manager[0] = scriptedManager(helper, new ScriptedBackend(List.of()), Files.createTempDirectory("aiciv-gametest"));
        Bot bot = manager[0].spawn(name, "scripted", "", helper.getLevel(), helper.absoluteVec(start));
        helper.onEachTick(manager[0]::tickStart);
        return bot;
    }

    static void removeBot(BotManager manager, String name) {
        try {
            manager.remove(name);
        } catch (com.aiciv.bot.BotException e) {
            throw new IllegalStateException(e);
        }
    }

    static String events(Bot bot) {
        return String.join("\n", bot.brain.events().lines());
    }

    private static void oakTree(GameTestHelper helper, int x, int z) {
        for (int y = 1; y <= 3; y++) helper.setBlock(x, y, z, Blocks.OAK_LOG);
    }

    private static int logsLeft(GameTestHelper helper, int x, int z) {
        int n = 0;
        for (int y = 1; y <= 3; y++) if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.OAK_LOG)) n++;
        return n;
    }

    @GameTest(maxTicks = 1500)
    public void minesLogsAcrossTwoTrees(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        oakTree(helper, 1, 2);
        oakTree(helper, 5, 2);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestMineLogs", new Vec3(3.5, 1, 5.5));
        bot.executor.replace(List.of(new MineAction("log", 5)), bot.context);
        helper.succeedWhen(() -> {
            int logs = InventoryOps.count(bot.player, s -> s.is(Items.OAK_LOG));
            helper.assertTrue(logs >= 5, "bot holds only " + logs + " oak_log; " + events(bot));
            helper.assertTrue(events(bot).contains("finished: mine oak_log x5"), "mine did not finish: " + events(bot));
            helper.assertTrue(logsLeft(helper, 1, 2) < 3, "the west tree is untouched");
            helper.assertTrue(logsLeft(helper, 5, 2) < 3, "the east tree is untouched");
            removeBot(manager[0], "TestMineLogs");
        });
    }

    @GameTest(maxTicks = 100)
    public void mineFailsFastWithoutTheRightTool(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        helper.setBlock(3, 1, 3, Blocks.COPPER_ORE);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestNoTool", new Vec3(1.5, 1, 1.5));
        bot.player.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        bot.executor.replace(List.of(new MineAction("copper_ore", 1)), bot.context);
        helper.runAfterDelay(20, () -> {
            String text = events(bot);
            boolean intact = helper.getBlockState(new BlockPos(3, 1, 3)).is(Blocks.COPPER_ORE);
            removeBot(manager[0], "TestNoTool");
            helper.assertTrue(text.contains("failed: mine") && text.contains("stone"), "expected a stone pickaxe hint: " + text);
            helper.assertTrue(intact, "the ore was dug anyway");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void mineFailsFastWhenNothingKnown(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestNothing", new Vec3(1.5, 1, 1.5));
        bot.executor.replace(List.of(new MineAction("diamond", 1)), bot.context);
        helper.runAfterDelay(20, () -> {
            String text = events(bot);
            removeBot(manager[0], "TestNothing");
            helper.assertTrue(text.contains("failed: mine") && text.contains("no diamond known nearby") && text.contains("explore"),
                    "expected an explore hint: " + text);
            helper.succeed();
        });
    }

    /**
     * Fairness: a coal ore known from a sealed cave, the bot in a parallel tunnel two stone away. The bot must dig
     * through the stone toward the ore before it mines it, never mine it through the wall.
     */
    @GameTest(maxTicks = 600)
    public void minesThroughTheWallInsteadOfReachingThroughIt(GameTestHelper helper) throws Exception {
        for (int x = 0; x <= 8; x++) for (int y = 0; y <= 3; y++) for (int z = 0; z <= 8; z++) helper.setBlock(x, y, z, Blocks.STONE);
        for (int x = 1; x <= 7; x++) for (int y = 1; y <= 2; y++) helper.setBlock(x, y, 2, Blocks.AIR); // the bot's tunnel
        for (int x = 3; x <= 5; x++) for (int y = 1; y <= 2; y++) helper.setBlock(x, y, 6, Blocks.AIR); // the sealed cave
        BlockPos ore = new BlockPos(4, 2, 5);
        BlockPos wallNear = new BlockPos(4, 2, 3), wallFar = new BlockPos(4, 2, 4);
        helper.setBlock(ore, Blocks.COAL_ORE);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestWallOre", new Vec3(4.5, 1, 2.5));
        BlockPos abs = helper.absolutePos(ore);
        bot.knownBlocks.see("coal_ore", new Pos(abs.getX(), abs.getY(), abs.getZ()), helper.getLevel().getGameTime());
        bot.player.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        bot.executor.replace(List.of(new MineAction("coal_ore", 1)), bot.context);
        boolean[] cheated = {false};
        helper.onEachTick(() -> {
            boolean wallIntact = helper.getBlockState(wallNear).is(Blocks.STONE) || helper.getBlockState(wallFar).is(Blocks.STONE);
            if (!helper.getBlockState(ore).is(Blocks.COAL_ORE) && wallIntact) cheated[0] = true;
        });
        helper.runAfterDelay(599, () -> {
            if (manager[0].bot("TestWallOre").isPresent()) removeBot(manager[0], "TestWallOre");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!cheated[0], "the ore was mined through the wall; " + events(bot));
            helper.assertTrue(!helper.getBlockState(ore).is(Blocks.COAL_ORE), "the ore is still there; " + events(bot));
            helper.assertTrue(events(bot).contains("finished: mine coal x1"), "mine did not finish: " + events(bot));
            helper.assertTrue(InventoryOps.has(bot.player, Items.COAL, 1), "no coal held; " + events(bot));
            removeBot(manager[0], "TestWallOre");
        });
    }

    @GameTest(maxTicks = 300)
    public void mineRetriesWhenTheBlockStateChangesMidBreak(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        helper.setBlock(2, 1, 2, Blocks.OAK_LOG);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestChanged", new Vec3(2.5, 1, 4.5));
        bot.executor.replace(List.of(new MineAction("log", 1)), bot.context);
        // Turning the log changes its state while it is being broken, which BlockBreaker reports as "block changed".
        helper.runAfterDelay(8, () -> helper.setBlock(2, 1, 2, Blocks.OAK_LOG.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X)));
        helper.succeedWhen(() -> {
            helper.assertTrue(events(bot).contains("finished: mine oak_log x1"), "mine did not finish: " + events(bot));
            helper.assertTrue(InventoryOps.count(bot.player, s -> s.is(Items.OAK_LOG)) >= 1, "no log was collected");
            removeBot(manager[0], "TestChanged");
        });
    }

    @GameTest(maxTicks = 400)
    public void collectPicksUpDroppedItems(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestCollect", new Vec3(1.5, 1, 3.5));
        for (int z : new int[] {2, 4, 6}) {
            Vec3 at = helper.absoluteVec(new Vec3(6.5, 1.2, z + 0.5));
            helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), at.x, at.y, at.z, new ItemStack(Items.AMETHYST_SHARD)));
        }
        bot.executor.replace(List.of(new CollectAction("amethyst_shard")), bot.context);
        helper.succeedWhen(() -> {
            int held = InventoryOps.count(bot.player, s -> s.is(Items.AMETHYST_SHARD));
            helper.assertTrue(held == 3, "bot holds " + held + " amethyst_shard; " + events(bot));
            helper.assertTrue(events(bot).contains("finished: collect amethyst_shard"), "collect did not finish: " + events(bot));
            removeBot(manager[0], "TestCollect");
        });
    }

    @GameTest(maxTicks = 400)
    public void exploreWalksNorth(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 16; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestExplore", new Vec3(3.5, 1, 15.5));
        double startZ = bot.player.getZ();
        // The goal circle has radius 8, so the target must lie more than 8 blocks ahead for the bot to move.
        bot.executor.replace(List.of(new ExploreAction("N", 14)), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(startZ - bot.player.getZ() >= 5.0, "bot only moved " + (startZ - bot.player.getZ()) + " north");
            helper.assertTrue(events(bot).contains("finished: explore N 14"), "explore did not finish: " + events(bot));
            removeBot(manager[0], "TestExplore");
        });
    }

    @GameTest(maxTicks = 1200)
    public void menuBotFollowsItsChoice(GameTestHelper helper) throws Exception {
        stoneFloor(helper);
        for (int y = 1; y <= 4; y++) helper.setBlock(5, y, 5, Blocks.BIRCH_LOG);
        JsonObject turn = JsonParser.parseString("""
                {"tool_calls": [{"id": "1", "type": "function",
                  "function": {"name": "choose", "arguments": "{\\"option\\": \\"mine_birch_log\\"}"}}]}""").getAsJsonObject();
        ScriptedBackend backend = new ScriptedBackend(List.of(turn));
        Path dir = Files.createTempDirectory("aiciv-gametest");
        AiCivConfig config = AiCivConfig.GSON.fromJson("""
                {"providers": {"scripted": {"type": "scripted", "script": "unused.json"}},
                 "models": {"scripted-menu": {"provider": "scripted", "model": "script", "decisionMode": "menu"}},
                 "defaultModel": "scripted-menu", "decisionGapSeconds": 0}""", AiCivConfig.class);
        LlmService llm = new LlmService(config, Map.of("scripted", backend), Clock.systemUTC());
        BotManager manager = new BotManager(helper.getLevel().getServer(), config, llm,
                dir.resolve("roster.json"), new DecisionLog(dir.resolve("logs")), Clock.systemUTC());
        Bot bot = manager.spawn("TestMenuBot", "scripted-menu", "", helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 1, 2.5)));
        helper.onEachTick(() -> {
            manager.tickStart();
            manager.tickEnd();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(InventoryOps.count(bot.player, s -> s.is(Items.BIRCH_LOG)) >= 1, "bot holds no birch_log; " + events(bot));
            JsonObject request = backend.requests().get(0);
            helper.assertTrue(request.getAsJsonArray("tools").size() == 1, "menu request did not carry the choose tool");
            String log;
            try {
                log = Files.readString(dir.resolve("logs").resolve("TestMenuBot.jsonl"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            helper.assertTrue(log.contains("\"mode\":\"menu\"") && log.contains("\"option\":\"mine_birch_log\""), log);
            removeBot(manager, "TestMenuBot");
        });
    }
}
