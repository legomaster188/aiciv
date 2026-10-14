package com.aiciv.gametest;

import com.aiciv.actions.ActionFactory;
import com.aiciv.bot.Bot;
import com.aiciv.building.Facing;
import com.aiciv.building.StructureRegistry;
import com.aiciv.dimension.Portals;
import com.aiciv.dimension.Waypoints;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.ToolModules;
import com.aiciv.nav.Pos;
import com.aiciv.nether.BuildPortalAction;
import com.aiciv.nether.FindFortressAction;
import com.aiciv.nether.HuntBlazesAction;
import com.aiciv.nether.PortalFrame;
import com.aiciv.world.InventoryOps;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

/** Task 2: portals, the nether, fortresses and blazes. Bot names start with TestNeth. */
public final class NetherGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("nether")), "nether module missing");
        helper.succeed();
    }

    private static void run(Bot bot, String tool, String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        ActionFactory.Result r = ActionFactory.create(ToolCall.of(tool, args));
        if (r instanceof ActionFactory.Result.Ok ok) bot.executor.replace(List.of(ok.action()), bot.context);
        else throw new IllegalStateException(tool + " was refused: " + ((ActionFactory.Result.Invalid) r).reason());
    }

    private static void arena(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 8);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) helper.setBlock(x, 0, z, Blocks.STONE);
    }

    private static Pos pos(BlockPos p) {
        return new Pos(p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos block(Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    /** Builds the ten obsidian frame cells at relative bottomLeft, facing south, except the cells whose index is in {@code skip}. */
    private static void frame(GameTestHelper helper, Pos bottomLeft, int... skip) {
        List<Pos> cells = PortalFrame.frameCells(bottomLeft, Facing.SOUTH);
        for (int i = 0; i < cells.size(); i++) {
            boolean skipped = false;
            for (int s : skip) skipped |= s == i;
            if (!skipped) helper.setBlock(block(cells.get(i)), Blocks.OBSIDIAN);
        }
    }

    private static int portalBlocks(GameTestHelper helper, Pos bottomLeft) {
        int n = 0;
        for (Pos p : PortalFrame.interior(bottomLeft, Facing.SOUTH)) if (helper.getBlockState(block(p)).is(Blocks.NETHER_PORTAL)) n++;
        return n;
    }

    @GameTest(maxTicks = 2400, padding = 24)
    public void buildsAndLightsPortalFromObsidian(GameTestHelper helper) {
        arena(helper);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethBuild", 4.5, 4.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.OBSIDIAN, 14));
        bot.player.getInventory().setItem(1, new ItemStack(Items.FLINT_AND_STEEL));
        BlockPos origin = helper.absolutePos(new BlockPos(4, 1, 8));
        String structure = BuildPortalAction.structureName(Portals.key(helper.getLevel()), origin);
        StructureRegistry.of(helper.getLevel().getServer()).store().remove(structure);
        run(bot, "build_portal", "{\"x\":" + origin.getX() + ",\"y\":" + origin.getY() + ",\"z\":" + origin.getZ() + ",\"facing\":\"S\"}");
        Pos bottomLeft = new Pos(4, 1, 8);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: built a nether portal at"), "not finished yet: " + events);
            helper.assertTrue(events.contains("(lit)"), events);
            for (Pos c : PortalFrame.frameCells(bottomLeft, Facing.SOUTH)) {
                helper.assertTrue(helper.getBlockState(block(c)).is(Blocks.OBSIDIAN), "frame cell " + c + " is not obsidian");
            }
            for (Pos c : PortalFrame.corners(bottomLeft, Facing.SOUTH)) {
                helper.assertTrue(helper.getBlockState(block(c)).is(Blocks.OBSIDIAN), "corner " + c + " is not obsidian");
            }
            helper.assertTrue(portalBlocks(helper, bottomLeft) == 6, "portal blocks: " + portalBlocks(helper, bottomLeft));
            helper.assertTrue(StructureRegistry.of(helper.getLevel().getServer()).store().get(structure).isPresent(), "structure " + structure + " missing");
            StructureRegistry.of(helper.getLevel().getServer()).store().remove(structure);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100, padding = 24)
    public void buildPortalFailsWithoutEnoughObsidian(GameTestHelper helper) {
        arena(helper);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethShort", 4.5, 4.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.OBSIDIAN, 9));
        bot.player.getInventory().setItem(1, new ItemStack(Items.FLINT_AND_STEEL));
        run(bot, "build_portal", "{\"facing\":\"S\"}");
        helper.runAfterDelay(5, () -> {
            String events = GameTestKit.events(bot);
            boolean changed = false;
            for (int x = 0; x < 8; x++) for (int y = 1; y <= 6; y++) for (int z = 4; z < 10; z++) changed |= !helper.getBlockState(new BlockPos(x, y, z)).isAir();
            GameTestKit.remove(s);
            helper.assertTrue(events.contains("needs 10 obsidian (you have 9)"), events);
            helper.assertTrue(!changed, "a block changed");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 600, padding = 24)
    public void lightsPortal(GameTestHelper helper) {
        arena(helper);
        Pos bottomLeft = new Pos(6, 1, 8);
        frame(helper, bottomLeft);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethLight", 4.5, 4.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
        run(bot, "light_portal", "{}");
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: lit the portal at"), "not lit yet: " + events);
            helper.assertTrue(portalBlocks(helper, bottomLeft) == 6, "portal blocks: " + portalBlocks(helper, bottomLeft));
            ItemStack flint = bot.player.getInventory().getItem(0);
            helper.assertTrue(flint.is(Items.FLINT_AND_STEEL) && flint.getDamageValue() >= 1, "flint damage " + flint.getDamageValue());
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600, padding = 24)
    public void lightPortalFailsOnBrokenFrame(GameTestHelper helper) {
        arena(helper);
        Pos bottomLeft = new Pos(6, 1, 8);
        frame(helper, bottomLeft, 9);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethBroken", 4.5, 4.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
        run(bot, "light_portal", "{}");
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("no complete empty portal frame"), "no failure yet: " + events);
            helper.assertTrue(portalBlocks(helper, bottomLeft) == 0, "a portal appeared");
            helper.assertTrue(bot.player.getInventory().getItem(0).getDamageValue() == 0, "the flint was used");
            GameTestKit.remove(s);
        });
    }

    /** Stopped the tick its first lava lands in the frame, the cast scoops the lava back and leaves no fluid behind. */
    @GameTest(maxTicks = 2400, padding = 24)
    public void cancelledCastLeavesNoFluid(GameTestHelper helper) {
        arena(helper);
        Pos bottomLeft = new Pos(4, 1, 8);
        List<Pos> cells = PortalFrame.frameCells(bottomLeft, Facing.SOUTH);
        long[] stoppedAt = {-1};
        GameTestKit.Spawned[] holder = new GameTestKit.Spawned[1];
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethUndo", 4.5, 4.5, () -> {
            if (holder[0] == null || stoppedAt[0] >= 0) return;
            for (Pos c : cells) {
                if (helper.getLevel().getFluidState(helper.absolutePos(block(c))).isSourceOfType(Fluids.LAVA)) {
                    holder[0].bot().executor.stop(holder[0].bot().context);
                    stoppedAt[0] = helper.getTick();
                    return;
                }
            }
        });
        holder[0] = s;
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.WATER_BUCKET));
        bot.player.getInventory().setItem(1, new ItemStack(Items.LAVA_BUCKET));
        bot.player.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 24));
        bot.player.getInventory().setItem(3, new ItemStack(Items.STONE_PICKAXE));
        BlockPos origin = helper.absolutePos(new BlockPos(4, 1, 8));
        run(bot, "cast_portal", "{\"x\":" + origin.getX() + ",\"y\":" + origin.getY() + ",\"z\":" + origin.getZ() + ",\"facing\":\"S\"}");
        helper.succeedWhen(() -> {
            helper.assertTrue(stoppedAt[0] >= 0, "no lava poured yet: " + GameTestKit.events(bot));
            helper.assertTrue(helper.getTick() >= stoppedAt[0] + 2, "give it a tick");
            for (int x = -1; x <= 9; x++) {
                for (int y = 1; y <= 7; y++) {
                    for (int z = 0; z <= 12; z++) {
                        helper.assertTrue(!helper.getLevel().getFluidState(helper.absolutePos(new BlockPos(x, y, z))).isSource(),
                                "a fluid source is left at " + x + " " + y + " " + z);
                    }
                }
            }
            helper.assertTrue(InventoryOps.has(bot.player, Items.LAVA_BUCKET, 1), "the lava was not scooped back");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 9600, padding = 24)
    public void castsPortalFromBuckets(GameTestHelper helper) {
        arena(helper);
        // A lava pool two deep, lined with stone, 8 blocks east of the bot: 18 sources for 10 frame cells.
        for (int x = 11; x <= 15; x++) {
            for (int z = 2; z <= 6; z++) {
                helper.setBlock(x, -2, z, Blocks.STONE);
                helper.setBlock(x, -1, z, Blocks.STONE);
                boolean pool = x >= 12 && x <= 14 && z >= 3 && z <= 5;
                if (pool) {
                    helper.setBlock(x, -1, z, Blocks.LAVA);
                    helper.setBlock(x, 0, z, Blocks.LAVA);
                }
            }
        }
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethCast", 4.5, 4.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.WATER_BUCKET));
        bot.player.getInventory().setItem(1, new ItemStack(Items.BUCKET));
        // 20 for the wall the fluids are poured against, and spare ones to climb on.
        bot.player.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 24));
        // Cobblestone only drops for a pickaxe, so taking the wall down needs one.
        bot.player.getInventory().setItem(3, new ItemStack(Items.STONE_PICKAXE));
        BlockPos origin = helper.absolutePos(new BlockPos(4, 1, 8));
        run(bot, "cast_portal", "{\"x\":" + origin.getX() + ",\"y\":" + origin.getY() + ",\"z\":" + origin.getZ() + ",\"facing\":\"S\"}");
        Pos bottomLeft = new Pos(4, 1, 8);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(!events.contains("failed:"), events);
            helper.assertTrue(events.contains("finished: cast a nether portal at"), "not finished yet: " + events);
            for (Pos c : PortalFrame.frameCells(bottomLeft, Facing.SOUTH)) {
                helper.assertTrue(helper.getBlockState(block(c)).is(Blocks.OBSIDIAN), "frame cell " + c + " is not obsidian");
            }
            int wallLeft = 0;
            for (Pos c : PortalFrame.wallCells(bottomLeft, Facing.SOUTH)) if (!helper.getBlockState(block(c)).isAir()) wallLeft++;
            helper.assertTrue(wallLeft <= 3, wallLeft + " wall blocks are still there");
            int cobble = InventoryOps.count(bot.player, st -> st.is(Items.COBBLESTONE));
            helper.assertTrue(cobble >= 17, "the bot holds " + cobble + " cobblestone");
            for (int x = 0; x <= 5; x++) {
                for (int y = 1; y <= 5; y++) {
                    for (int z = 7; z <= 9; z++) {
                        helper.assertTrue(helper.getLevel().getFluidState(helper.absolutePos(new BlockPos(x, y, z))).isEmpty(), "fluid at " + x + " " + y + " " + z);
                    }
                }
            }
            for (Pos c : PortalFrame.interior(bottomLeft, Facing.SOUTH)) {
                helper.assertTrue(helper.getBlockState(block(c)).isAir(), "interior " + c + " is not air");
            }
            helper.assertTrue(InventoryOps.has(bot.player, Items.WATER_BUCKET, 1), "no water_bucket at the end");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600, padding = 24)
    public void findsFortressBricks(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 8);
        for (int x = 0; x < 24; x++) for (int z = 0; z < 16; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        for (int y = 1; y <= 3; y++) for (int z = 7; z <= 9; z++) helper.setBlock(19, y, z, Blocks.NETHER_BRICKS);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 24, 16);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethFort", 1.5, 8.5);
        Bot bot = s.bot();
        bot.executor.replace(List.of(new FindFortressAction("E", 64, true)), bot.context);
        BlockPos wall = helper.absolutePos(new BlockPos(19, 2, 8));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: found a nether fortress at"), "not found yet: " + events);
            Waypoints waypoints = bot.attachments.find(Waypoints.class).orElseThrow();
            Waypoints.Waypoint w = waypoints.nearest(Waypoints.FORTRESS, Portals.key(bot.player.level()), pos(wall))
                    .orElseThrow(() -> new IllegalStateException("no fortress waypoint"));
            double d = Math.sqrt(Math.pow(w.pos().x() - wall.getX(), 2) + Math.pow(w.pos().y() - wall.getY(), 2) + Math.pow(w.pos().z() - wall.getZ(), 2));
            helper.assertTrue(d <= 3, "fortress waypoint " + w.pos() + " is " + d + " from the wall");
            release.run();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1800, padding = 24)
    public void huntsBlazesWithSword(GameTestHelper helper) {
        arena(helper);
        // A mob in a chunk that is not entity-ticking keeps its hit cooldown and its drops keep their pickup delay.
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 16, 16);
        List<Blaze> blazes = new ArrayList<>();
        blazes.add(helper.spawnWithNoFreeWill(EntityTypes.BLAZE, 6.5f, 1.0f, 4.5f));
        blazes.add(helper.spawnWithNoFreeWill(EntityTypes.BLAZE, 7.5f, 1.0f, 6.5f));
        blazes.add(helper.spawnWithNoFreeWill(EntityTypes.BLAZE, 5.5f, 1.0f, 7.5f));
        // One sword hit each: a hit blaze is knocked into the air and can end up anywhere, which says nothing about the hunt.
        for (Blaze b : blazes) b.setHealth(5.0F);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethBlaze", 3.5, 4.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        // More rods than three blazes can drop, so the hunt only ends when no blaze is left; a rod drops half the time.
        bot.executor.replace(List.of(new HuntBlazesAction(16, true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: got") || events.contains("failed: hunt blazes: no blazes in sight"), "not over yet: " + events);
            for (Blaze b : blazes) {
                helper.assertTrue(!b.isAlive(), "a blaze is still alive at " + b.position() + " hp " + b.getHealth() + "; bot at " + bot.player.position() + "; " + events);
            }
            ServerLevel level = helper.getLevel();
            List<ItemEntity> rods = level.getEntitiesOfClass(ItemEntity.class, bot.player.getBoundingBox().inflate(8), e -> e.getItem().is(Items.BLAZE_ROD));
            helper.assertTrue(rods.isEmpty(), rods.size() + " blaze rods were left lying");
            release.run();
            GameTestKit.remove(s);
        });
    }

    /** No blaze in sight at the start of a long brick corridor: the hunt follows the bricks until it finds the one at the far end. */
    @GameTest(maxTicks = 1500, padding = 24)
    public void huntFollowsTheFortressToFindBlazes(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 8);
        for (int x = -8; x <= 44; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(x, -1, z, Blocks.STONE);
                helper.setBlock(x, 0, z, Blocks.NETHER_BRICKS);
            }
        }
        Runnable release = GameTestKit.keepEntitiesTicking(helper, -8, 0, 44, 4);
        Blaze blaze = helper.spawnWithNoFreeWill(EntityTypes.BLAZE, 38.5f, 1.0f, 2.5f);
        blaze.setHealth(5.0F);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethExplore", -6.5, 2.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        bot.executor.replace(List.of(new HuntBlazesAction(16, true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(!blaze.isAlive(), "the blaze at the far end is alive; bot at " + bot.player.position() + "; " + events);
            release.run();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1500, padding = 24)
    public void goToNetherLightsTheFrameAndEntersIt(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        arena(helper);
        Pos bottomLeft = new Pos(6, 1, 8);
        frame(helper, bottomLeft);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestNethGoTo", 4.5, 4.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
        run(bot, "go_to_nether", "{}");
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            if (nether == null) {
                helper.assertTrue(events.contains("the_nether does not exist on this server"), "unexpected: " + events);
                GameTestKit.remove(s);
                return;
            }
            helper.assertTrue(events.contains("finished: went through to the_nether"), "not through yet: " + events);
            helper.assertTrue(bot.player.level().dimension() == Level.NETHER, "the bot is in " + Portals.key(bot.player.level()));
            helper.assertTrue(portalBlocks(helper, bottomLeft) == 6, "the portal is not lit");
            GameTestKit.remove(s);
        });
    }
}
