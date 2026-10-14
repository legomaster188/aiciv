package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Sets a minecart from the inventory on a rail: the given one, or the nearest the bot can see. */
public final class PlaceMinecartAction implements Action {
    private static final int RADIUS = 8;
    private static final double STAND_RADIUS = 4.0;
    private final BlockPos given;
    private BlockPos rail;
    private Approach approach;
    private String result;

    public PlaceMinecartAction(BlockPos railOrNull) {
        this.given = railOrNull;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (rail == null) {
            if (!InventoryOps.has(p, Items.MINECART, 1)) return ActionStatus.failed("you carry no minecart");
            if (given != null) {
                if (!p.level().getBlockState(given).is(BlockTags.RAILS)) return ActionStatus.failed("no rail at " + given.getX() + " " + given.getY() + " " + given.getZ());
                rail = given;
            } else {
                List<BlockPos> rails = Finder.visibleStates(p, s -> s.is(BlockTags.RAILS), RADIUS);
                if (rails.isEmpty()) return ActionStatus.failed("no rail within " + RADIUS + " blocks");
                rail = rails.get(0);
            }
            approach = Approach.block(rail, STAND_RADIUS);
        }
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        InventoryOps.selectBest(p, st -> st.is(Items.MINECART) ? 1 : 0);
        BlockUse.Result r = BlockUse.click(p, rail);
        if (r == BlockUse.Result.OUT_OF_REACH || r == BlockUse.Result.NOT_VISIBLE) {
            approach = Approach.block(rail, 2.5);
            return ActionStatus.RUNNING;
        }
        Vec3 at = Vec3.atCenterOf(rail);
        if (p.level().getEntitiesOfClass(AbstractMinecart.class, new AABB(at, at).inflate(1.5)).isEmpty()) {
            return ActionStatus.failed("could not put the minecart on the rail at " + rail.getX() + " " + rail.getY() + " " + rail.getZ());
        }
        result = "placed a minecart at " + rail.getX() + " " + rail.getY() + " " + rail.getZ();
        return ActionStatus.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (rail != null) approach = Approach.block(rail, STAND_RADIUS);
    }

    @Override
    public String describe() {
        return result != null ? result : "place a minecart";
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }
}
