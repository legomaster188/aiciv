package com.aiciv.actions;

import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.nav.Pos;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.InventoryOps;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/** Walks within reach of a cell and places a held block there, as a player right-clicking a neighbouring face would. */
public final class PlaceAction implements Action {
    static final double STAND_RADIUS = 3.5, CLOSE_RADIUS = 2.0;
    static final int REFUSED_TICKS = 20;

    private final String name;
    private final BlockPos target;
    private Item item;
    private boolean started;
    private boolean closer;
    private int refused;

    public PlaceAction(String block, int x, int y, int z) {
        String q = block.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        this.name = q.replaceAll("\\s+", "_");
        this.target = new BlockPos(x, y, z);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer player = ctx.player();
        ServerLevel level = player.level();
        if (!started) {
            item = lookUp(name);
            if (item == null) return ActionStatus.failed("no block called " + name);
            if (!level.getBlockState(target).canBeReplaced()) return ActionStatus.failed("occupied: " + describeAt(level));
            if (InventoryOps.count(player, s -> s.is(item)) < 1) return ActionStatus.failed("no " + name + " in inventory");
            started = true;
            approach(ctx, STAND_RADIUS);
            return ActionStatus.RUNNING;
        }
        NavStatus nav = ctx.navigator().status();
        if (nav.state() == NavStatus.State.RUNNING) return ActionStatus.RUNNING;
        if (nav.state() == NavStatus.State.FAILED) return ActionStatus.failed(nav.reason());
        if (standingIn(player)) {
            // Placing into the bot's own body is refused by the game; step away first.
            Vec3 c = Vec3.atCenterOf(target);
            ctx.navigator().goTo(new NearGoal(c.x + 2, target.getY(), c.z, 1.0));
            return ActionStatus.RUNNING;
        }
        switch (BlockPlacer.place(player, target, s -> s.is(item))) {
            case PLACED:
                if (ctx.knownBlocks() != null && isStation(name)) {
                    ctx.knownBlocks().see(name, new Pos(target.getX(), target.getY(), target.getZ()), level.getGameTime());
                }
                return ActionStatus.DONE;
            case NO_ITEM:
                return ActionStatus.failed("no " + name + " in inventory");
            case OCCUPIED:
                return ActionStatus.failed("occupied: " + describeAt(level));
            case NO_SUPPORT:
                return ActionStatus.failed("nothing to place it against at " + coords());
            case OUT_OF_REACH:
                if (closer) return ActionStatus.failed("could not get within reach of " + coords());
                closer = true;
                approach(ctx, CLOSE_RADIUS);
                return ActionStatus.RUNNING;
            default:
                // Usually an entity standing in the cell; give it a moment to move.
                if (++refused >= REFUSED_TICKS) return ActionStatus.failed("could not place " + name + " at " + coords() + " - something is in the way");
                return ActionStatus.RUNNING;
        }
    }

    private void approach(BotContext ctx, double radius) {
        ctx.navigator().goTo(new NearGoal(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, radius));
    }

    private boolean standingIn(AiPlayer player) {
        BlockPos feet = player.blockPosition();
        return feet.equals(target) || feet.above().equals(target);
    }

    private String describeAt(ServerLevel level) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).getPath() + " is at " + coords();
    }

    private String coords() {
        return target.getX() + " " + target.getY() + " " + target.getZ();
    }

    private static boolean isStation(String id) {
        return id.equals("crafting_table") || id.equals("furnace") || id.equals("chest") || id.endsWith("_bed");
    }

    private static Item lookUp(String id) {
        Identifier key = Identifier.tryParse("minecraft:" + id);
        if (key != null && BuiltInRegistries.ITEM.containsKey(key) && BuiltInRegistries.BLOCK.containsKey(key)) {
            return BuiltInRegistries.ITEM.getValue(key);
        }
        return null;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "place " + name + " at " + coords();
    }

    @Override
    public int timeoutTicks() {
        return 30 * 20;
    }
}
