package com.aiciv.gametest;

import com.aiciv.actions.ActionFactory;
import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.dragon.BreakCageAction;
import com.aiciv.dragon.HitDragonAction;
import com.aiciv.dragon.ShootCrystalsAction;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.ToolModules;
import com.google.gson.JsonObject;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Task 4: the ender dragon. Bot names start with TestDrag. */
public final class DragonGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("dragon")), "dragon module missing");
        helper.succeed();
    }

    @GameTest(maxTicks = 600, padding = 24)
    public void shootsEndCrystalFromSafeDistance(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 18);
        for (int x = 0; x < 18; x++) for (int z = 0; z < 9; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 17, 8);
        for (int y = 1; y <= 4; y++) helper.setBlock(14, y, 4, Blocks.OBSIDIAN);
        Vec3 at = helper.absoluteVec(new Vec3(14.5, 5, 4.5));
        EndCrystal crystal = new EndCrystal(helper.getLevel(), at.x, at.y, at.z);
        crystal.setShowBottom(false);
        helper.getLevel().addFreshEntity(crystal);
        double[] closest = {Double.MAX_VALUE};
        GameTestKit.Spawned[] holder = new GameTestKit.Spawned[1];
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDragShoot", 1.5, 4.5, () -> {
            if (holder[0] != null && !crystal.isRemoved()) closest[0] = Math.min(closest[0], holder[0].bot().player.position().distanceTo(crystal.position()));
        });
        holder[0] = s;
        Bot bot = s.bot();
        AiPlayer p = bot.player;
        p.getInventory().setItem(0, new ItemStack(Items.BOW));
        p.getInventory().setItem(1, new ItemStack(Items.ARROW, 8));
        p.setHealth(20f);
        bot.executor.replace(List.of(new ShootCrystalsAction(1, true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(crystal.isRemoved(), "the crystal is still there: " + events);
            helper.assertTrue(events.contains("finished: destroyed 1 crystal (0 left in sight)"), "not finished: " + events);
            helper.assertTrue(closest[0] >= 9, "the bot came within " + closest[0] + " of the crystal");
            helper.assertTrue(p.getHealth() >= 14, "health " + p.getHealth());
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 800, padding = 24)
    public void hitsPerchedDragonHead(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 24);
        // The dragon smashes every block in its way except the ones it is immune to, obsidian among them.
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) helper.setBlock(x, 0, z, Blocks.OBSIDIAN);
        // Every 100 ticks a scanning dragon charges for one tick and its wings throw the bot about; walls keep it on the floor.
        for (int i = 0; i < 24; i++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(i, y, 0, Blocks.OBSIDIAN);
                helper.setBlock(i, y, 23, Blocks.OBSIDIAN);
                helper.setBlock(0, y, i, Blocks.OBSIDIAN);
                helper.setBlock(23, y, i, Blocks.OBSIDIAN);
            }
        }
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 23, 23);
        EnderDragon dragon = helper.spawn(EntityTypes.ENDER_DRAGON, new Vec3(12.5, 1, 14.5));
        dragon.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDragHit", 12.5, 8.5, () -> {
            if (dragon.getPhaseManager().getCurrentPhase().getPhase() != EnderDragonPhase.SITTING_SCANNING) dragon.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING);
        });
        Bot bot = s.bot();
        AiPlayer p = bot.player;
        p.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        // A fresh dragon's parts are placed from a 64-tick flight history; let it fill before the fight starts.
        helper.runAfterDelay(80, () -> bot.executor.replace(List.of(new HitDragonAction(10, true)), bot.context));
        helper.succeedWhen(() -> {
            helper.assertTrue(dragon.getHealth() < dragon.getMaxHealth(),
                    "the dragon is unhurt: dragon at " + dragon.position() + ", head at " + dragon.head.position() + ", bot at "
                            + p.position() + ": " + GameTestKit.events(bot));
            GameTestKit.remove(s);
            dragon.discard();
            release.run();
        });
    }

    @GameTest(maxTicks = 100, padding = 24)
    public void stepsOutOfDragonBreath(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 24);
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        Vec3 at = helper.absoluteVec(new Vec3(12.5, 1, 12.5));
        AreaEffectCloud cloud = new AreaEffectCloud(helper.getLevel(), at.x, at.y, at.z);
        cloud.setCustomParticle(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0f));
        cloud.setRadius(3f);
        cloud.setWaitTime(0);
        cloud.setDuration(400);
        helper.getLevel().addFreshEntity(cloud);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDragBreath", 12.5, 12.5);
        Bot bot = s.bot();
        bot.executor.replace(List.of(new HitDragonAction(5, true)), bot.context);
        helper.succeedWhen(() -> {
            double apart = Math.hypot(bot.player.getX() - cloud.getX(), bot.player.getZ() - cloud.getZ());
            helper.assertTrue(apart >= 4, "still " + apart + " from the cloud centre: " + GameTestKit.events(bot));
            cloud.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 3000, padding = 24)
    public void breaksCageAndShootsCrystal(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 20);
        for (int x = 0; x < 20; x++) for (int z = 0; z < 14; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 19, 13);
        // A guarded tower: obsidian to y 9, the crystal at y 10 inside a ring of bars with a roof, 14.5 / 6.5 in the middle.
        for (int y = 1; y <= 9; y++) for (int x = 13; x <= 15; x++) for (int z = 5; z <= 7; z++) helper.setBlock(x, y, z, Blocks.OBSIDIAN);
        for (int y = 9; y <= 12; y++) {
            for (int x = 12; x <= 16; x++) {
                for (int z = 4; z <= 8; z++) {
                    boolean ring = x == 12 || x == 16 || z == 4 || z == 8;
                    if (ring || y == 12) helper.setBlock(x, y, z, Blocks.IRON_BARS);
                }
            }
        }
        Vec3 at = helper.absoluteVec(new Vec3(14.5, 10, 6.5));
        EndCrystal crystal = new EndCrystal(helper.getLevel(), at.x, at.y, at.z);
        crystal.setShowBottom(false);
        helper.getLevel().addFreshEntity(crystal);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDragCage", 1.5, 6.5);
        Bot bot = s.bot();
        AiPlayer p = bot.player;
        p.getInventory().setItem(0, new ItemStack(Items.BOW));
        p.getInventory().setItem(1, new ItemStack(Items.ARROW, 8));
        p.getInventory().setItem(2, new ItemStack(Items.IRON_PICKAXE));
        p.getInventory().setItem(3, new ItemStack(Items.COBBLESTONE, 64));
        bot.executor.replace(List.of(new BreakCageAction(true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(crystal.isRemoved(), "the crystal is still there: " + events);
            helper.assertTrue(events.contains("finished: broke the cage and destroyed the crystal at"), "not finished: " + events);
            helper.assertTrue(p.getY() <= helper.absoluteVec(new Vec3(0, 3, 0)).y, "still up at y " + p.getY());
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 100)
    public void fightDragonOnlyInTheEnd(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDragFight", 3.5, 3.5);
        Bot bot = s.bot();
        ActionFactory.Result r = ActionFactory.create(ToolCall.of("fight_dragon", new JsonObject()));
        bot.executor.replace(List.of(((ActionFactory.Result.Ok) r).action()), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("works in the_end; you are in overworld"), "events: " + events);
            GameTestKit.remove(s);
        });
    }
}
