package com.aiciv.gametest;

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
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotFormatter;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.InventoryOps;
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
}
