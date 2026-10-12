package com.aiciv.gametest;

import static com.aiciv.gametest.AiCivGameTests.actionBot;
import static com.aiciv.gametest.AiCivGameTests.events;
import static com.aiciv.gametest.AiCivGameTests.removeBot;

import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.combat.CombatAction;
import com.aiciv.combat.CombatMoves;
import com.aiciv.modules.ToolModules;
import com.aiciv.world.InventoryOps;
import com.google.gson.JsonArray;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Short exact combat moves (Task 4). Bot names start with TestMove. */
public final class CombatMoveGameTests {
    private static int easyUsers;
    private static Difficulty difficultyBefore;

    /** Hostile mobs vanish in peaceful, so tests that need them switch to easy and put the setting back after. */
    private static void easy(MinecraftServer server) {
        if (easyUsers++ == 0) {
            difficultyBefore = server.overworld().getDifficulty();
            server.setDifficulty(Difficulty.EASY, true);
        }
    }

    private static void restoreDifficulty(MinecraftServer server) {
        if (--easyUsers == 0) server.setDifficulty(difficultyBefore, true);
    }

    /** One test's bot, zombie and the clean-up that must run exactly once. */
    private static final class Fight {
        final GameTestHelper helper;
        final MinecraftServer server;
        final String botName;
        final BotManager[] manager = new BotManager[1];
        Bot bot;
        Mob zombie;
        private boolean cleaned;

        Fight(GameTestHelper helper, String botName, Vec3 botAt, Vec3 zombieAt, int maxTicks) throws Exception {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.botName = botName;
            for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 0, z, Blocks.DIRT);
            easy(server);
            bot = actionBot(helper, manager, botName, botAt);
            zombie = helper.spawn(EntityTypes.ZOMBIE, zombieAt);
            zombie.setNoAi(true);
            zombie.setPersistenceRequired();
            zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // daylight must not do the damage
            helper.runAfterDelay(maxTicks - 1, this::cleanUp);
        }

        void faceZombie() {
            bot.player.lookAt(EntityAnchorArgument.Anchor.EYES, zombie.getEyePosition());
        }

        void run(String... moves) {
            JsonArray array = new JsonArray();
            for (String m : moves) array.add(m);
            bot.executor.replace(List.of(new CombatAction(CombatMoves.parse(array))), bot.context);
        }

        boolean finished() {
            return events(bot).contains("finished: combat");
        }

        double distance() {
            return bot.player.position().subtract(zombie.position()).horizontalDistance();
        }

        void cleanUp() {
            if (cleaned) return;
            cleaned = true;
            restoreDifficulty(server);
            zombie.discard();
            if (manager[0].bot(botName).isPresent()) removeBot(manager[0], botName);
        }
    }

    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("combat")), "the combat module is not registered");
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void strafeMovesSideways(GameTestHelper helper) throws Exception {
        Fight f = new Fight(helper, "TestMoveStrafe", new Vec3(3.5, 1, 6.5), new Vec3(3.5, 1, 3.5), 100);
        f.faceZombie();
        Vec3 start = f.bot.player.position();
        double before = f.distance();
        f.run("strafe left 10");
        helper.succeedWhen(() -> {
            helper.assertTrue(f.finished(), "strafe did not finish: " + events(f.bot));
            double sideways = Math.abs(f.bot.player.getX() - start.x);
            helper.assertTrue(sideways >= 0.8, "moved only " + sideways + " sideways");
            double change = Math.abs(f.distance() - before);
            helper.assertTrue(change < 1.0, "distance to the zombie changed by " + change);
            f.cleanUp();
        });
    }

    @GameTest(maxTicks = 100)
    public void backOffIncreasesDistance(GameTestHelper helper) throws Exception {
        Fight f = new Fight(helper, "TestMoveBack", new Vec3(3.5, 1, 5.5), new Vec3(3.5, 1, 2.5), 100);
        f.faceZombie();
        double before = f.distance();
        f.run("back_off 10");
        helper.succeedWhen(() -> {
            helper.assertTrue(f.finished(), "back_off did not finish: " + events(f.bot));
            double grew = f.distance() - before;
            helper.assertTrue(grew >= 1.0, "distance grew only " + grew);
            f.cleanUp();
        });
    }

    @GameTest(maxTicks = 200)
    public void jumpCritDealsCritDamage(GameTestHelper helper) throws Exception {
        Fight f = new Fight(helper, "TestMoveCrit", new Vec3(3.5, 1, 5.5), new Vec3(3.5, 1, 3.5), 200);
        f.bot.player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        f.faceZombie();
        f.run("jump_crit zombie");
        helper.succeedWhen(() -> {
            helper.assertTrue(f.finished(), "jump_crit did not finish: " + events(f.bot));
            float lost = f.zombie.getMaxHealth() - f.zombie.getHealth();
            helper.assertTrue(lost >= 8.0F, "the zombie lost only " + lost + " health");
            f.cleanUp();
        });
    }

    @GameTest(maxTicks = 100)
    public void shieldBlocksWhileRaised(GameTestHelper helper) throws Exception {
        Fight f = new Fight(helper, "TestMoveShield", new Vec3(3.5, 1, 5.5), new Vec3(3.5, 1, 2.5), 100);
        f.bot.player.getInventory().setItem(2, new ItemStack(Items.SHIELD));
        f.run("shield 30");
        int[] tick = {0};
        int[] blockingSince = {-1};
        helper.onEachTick(() -> {
            tick[0]++;
            if (f.bot.player.isBlocking() && blockingSince[0] < 0) blockingSince[0] = tick[0];
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(f.finished(), "shield did not finish: " + events(f.bot));
            helper.assertTrue(blockingSince[0] > 0 && blockingSince[0] <= 10, "blocking began at tick " + blockingSince[0]);
            helper.assertTrue(!f.bot.player.isBlocking(), "still blocking after the action");
            helper.assertTrue(f.bot.player.getOffhandItem().is(Items.SHIELD), "the shield is not in the offhand");
            f.cleanUp();
        });
    }

    @GameTest(maxTicks = 160)
    public void bowShootsAtTarget(GameTestHelper helper) throws Exception {
        Fight f = new Fight(helper, "TestMoveBow", new Vec3(0.5, 1, 0.5), new Vec3(6.5, 1, 6.5), 160);
        f.bot.player.getInventory().setItem(0, new ItemStack(Items.BOW));
        f.bot.player.getInventory().setItem(8, new ItemStack(Items.ARROW, 5));
        f.faceZombie();
        f.run("bow zombie 20");
        helper.succeedWhen(() -> {
            int arrows = InventoryOps.count(f.bot.player, s -> s.is(Items.ARROW));
            helper.assertTrue(arrows == 4, "the bot has " + arrows + " arrows; " + events(f.bot));
            helper.assertTrue(f.zombie.getHealth() < f.zombie.getMaxHealth(), "the zombie is unhurt; " + events(f.bot));
            f.cleanUp();
        });
    }

    @GameTest(maxTicks = 40)
    public void hotbarSelectsSlot(GameTestHelper helper) throws Exception {
        Fight f = new Fight(helper, "TestMoveHotbar", new Vec3(3.5, 1, 5.5), new Vec3(3.5, 1, 2.5), 40);
        f.run("hotbar 3");
        helper.succeedWhen(() -> {
            helper.assertTrue(f.finished(), "hotbar did not finish: " + events(f.bot));
            helper.assertTrue(f.bot.player.getInventory().getSelectedSlot() == 2, "slot " + f.bot.player.getInventory().getSelectedSlot());
            f.cleanUp();
        });
    }
}
