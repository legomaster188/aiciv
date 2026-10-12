package com.aiciv.gametest;

import static com.aiciv.gametest.AiCivGameTests.events;
import static com.aiciv.gametest.AiCivGameTests.removeBot;
import static com.aiciv.gametest.GameTestKit.decisionLines;
import static com.aiciv.gametest.GameTestKit.turn;

import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.world.InventoryOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The milestone-2 acceptance run: a scripted model drives a bot from bare hands to an iron pickaxe. */
public final class SurvivalGameTests {
    private static final int SIZE = 16;

    private static void arena(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, SIZE);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) helper.setBlock(x, 0, z, (x + z) % 3 == 0 ? Blocks.DIRT : Blocks.STONE);
        }
        for (int[] tree : new int[][] {{2, 3}, {3, 13}, {13, 2}}) {
            for (int y = 1; y <= 4; y++) helper.setBlock(tree[0], y, tree[1], Blocks.OAK_LOG);
        }
        for (int x = 8; x < 11; x++) {
            helper.setBlock(x, 1, 13, Blocks.STONE);
            helper.setBlock(x, 1, 14, Blocks.STONE);
        }
        helper.setBlock(12, 1, 12, Blocks.COAL_ORE);
        helper.setBlock(13, 1, 12, Blocks.COAL_ORE);
        for (int x = 5; x < 8; x++) helper.setBlock(x, 1, 6, Blocks.IRON_ORE);
    }

    @GameTest(maxTicks = 6000, padding = 24)
    public void scriptedBotReachesIronTools(GameTestHelper helper) throws Exception {
        arena(helper);
        ScriptedBackend backend = new ScriptedBackend(List.of(
                turn("mine", "{\"block\": \"log\", \"count\": 6}"),
                turn("craft", "{\"item\": \"wooden_pickaxe\", \"count\": 1}"),
                turn("mine", "{\"block\": \"stone\", \"count\": 11}"),
                turn("craft", "{\"item\": \"stone_pickaxe\", \"count\": 1}", "mine", "{\"block\": \"coal\", \"count\": 2}"),
                turn("mine", "{\"block\": \"iron\", \"count\": 3}"),
                turn("smelt", "{\"item\": \"raw_iron\", \"count\": 3}"),
                turn("craft", "{\"item\": \"iron_pickaxe\", \"count\": 1}")));
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = AiCivGameTests.scriptedManager(helper, backend, dir);
        Bot bot = manager.spawn("TestSmith", "scripted", "A determined survivor.", helper.getLevel(), helper.absoluteVec(new Vec3(8.5, 1, 8.5)));
        helper.onEachTick(() -> {
            manager.tickStart();
            manager.tickEnd();
        });
        helper.runAfterDelay(5999, () -> {
            if (manager.bot("TestSmith").isPresent()) removeBot(manager, "TestSmith");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(InventoryOps.has(bot.player, Items.IRON_PICKAXE, 1),
                    "no iron pickaxe yet after " + backend.requests().size() + " requests; " + events(bot));
            List<String> decisions = decisionLines(dir, "TestSmith");
            helper.assertTrue(decisions.size() >= 7, "only " + decisions.size() + " decisions logged");
            for (String line : decisions) helper.assertTrue(!line.contains("\"error\""), "a decision logged an error: " + line);
            removeBot(manager, "TestSmith");
        });
    }
}
