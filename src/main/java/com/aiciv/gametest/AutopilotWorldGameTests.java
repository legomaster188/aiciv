package com.aiciv.gametest;

import com.aiciv.world.InventoryOps;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Autopilot world goals: home, storage, farm, trade, defence, manners (Task 5). Bot names start with TestApWorld. */
public final class AutopilotWorldGameTests {
    @GameTest
    public void classLoads(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(maxTicks = 600)
    public void socialGivesRequestedItem(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.AutoSpawned giver = GameTestKit.spawnAutopilot(helper, "TestApWorldGive", 2.5, 2.5, "autopilot-chatty");
        GameTestKit.Spawned asker = GameTestKit.spawn(helper, "TestApWorldAsk", 6.5, 6.5);
        giver.bot().player.getInventory().setItem(10, new ItemStack(Items.BREAD, 20));
        // The asker lives in its own manager, so its chat goes through the giver's manager as a player's would.
        helper.runAfterDelay(5, () -> giver.manager().onChat(asker.bot().player, "TestApWorldGive give me 3 bread"));
        helper.succeedWhen(() -> {
            int bread = InventoryOps.count(asker.bot().player, st -> st.is(Items.BREAD));
            List<String> lines = GameTestKit.decisionLines(giver.dir(), "TestApWorldGive");
            helper.assertTrue(bread == 3, "TestApWorldAsk holds " + bread + " bread; giver log: " + lines);
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains("\"goal\":\"social\"")), "no social decision; log: " + lines);
            GameTestKit.remove(giver);
            GameTestKit.remove(asker);
        });
    }

    @GameTest(maxTicks = 1600)
    public void farmerTillsAndPlants(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        helper.setBlock(4, 0, 4, Blocks.WATER);
        GameTestKit.AutoSpawned s = GameTestKit.spawnAutopilot(helper, "TestApWorldFarm", 2.5, 2.5, "autopilot-farmer");
        var inv = s.bot().player.getInventory();
        inv.setItem(0, new ItemStack(Items.WOODEN_HOE));
        inv.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 4));
        inv.setItem(2, new ItemStack(Items.BREAD, 8));
        // Tools of every tier so that the tool goals, which outweigh farming for this personality, have nothing left to do.
        inv.setItem(3, new ItemStack(Items.IRON_PICKAXE));
        inv.setItem(4, new ItemStack(Items.IRON_SWORD));
        helper.succeedWhen(() -> {
            int crops = 0;
            for (int x = 0; x < 8; x++) {
                for (int z = 0; z < 8; z++) {
                    if (helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.WHEAT)
                            && helper.getBlockState(new BlockPos(x, 0, z)).is(Blocks.FARMLAND)) crops++;
                }
            }
            List<String> lines = GameTestKit.decisionLines(s.dir(), "TestApWorldFarm");
            helper.assertTrue(crops >= 3, "only " + crops + " wheat crops on farmland; log: " + lines);
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains("\"goal\":\"farm\"")), "no farm decision; log: " + lines);
            GameTestKit.remove(s);
        });
    }

    /** A calm enderman is no threat: a guard next to one leaves it alone (a zombie it kills: AutopilotGameTests). */
    @GameTest(maxTicks = 400, environment = "aiciv:day")
    public void guardLeavesCalmEndermanAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        CombatGameTests.easy(server);
        GameTestKit.AutoSpawned s = GameTestKit.spawnAutopilotInGameTime(helper, "TestApWorldCalm", 1.5, 1.5, "autopilot-guard");
        var inv = s.bot().player.getInventory();
        inv.setItem(0, new ItemStack(Items.IRON_SWORD));
        inv.setItem(1, new ItemStack(Items.BREAD, 8));
        Mob enderman = helper.spawn(EntityTypes.ENDERMAN, new Vec3(4.5, 1, 4.5));
        enderman.setNoAi(true); // no teleporting, no stare checks: it stays calm unless the bot hits it
        enderman.setPersistenceRequired();
        helper.runAfterDelay(300, () -> {
            List<String> lines = GameTestKit.decisionLines(s.dir(), "TestApWorldCalm");
            boolean hurt = !enderman.isAlive() || enderman.getHealth() < enderman.getMaxHealth();
            enderman.discard();
            GameTestKit.remove(s);
            CombatGameTests.restoreDifficulty(server);
            helper.assertTrue(!hurt, "the guard attacked a calm enderman; log: " + lines);
            helper.assertTrue(lines.stream().noneMatch(l -> l.contains("\"target\":\"enderman\"")), "an attack on the enderman: " + lines);
            helper.assertTrue(!lines.isEmpty(), "the guard never decided anything");
            helper.succeed();
        });
    }
}
