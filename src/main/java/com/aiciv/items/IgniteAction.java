package com.aiciv.items;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import com.aiciv.world.BlockUse;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.PlacedBlocks;
import com.aiciv.world.PlacementStore;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Lights TNT, a campfire or a candle with flint and steel or a fire charge, or sets fire on top of any other block. */
public final class IgniteAction extends ReachAction {
    private static final double SAFE_DISTANCE = 9.0;
    private static final int FLEE_TICKS = 100;

    private enum Kind { TNT, CAMPFIRE, CANDLE, OTHER }

    private final int x, y, z;
    private Kind kind;
    private int fled;
    private boolean primed;

    public IgniteAction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    protected String prepare(AiPlayer p) {
        if (!InventoryOps.has(p, Items.FLINT_AND_STEEL, 1) && !InventoryOps.has(p, Items.FIRE_CHARGE, 1)) {
            return "needs flint_and_steel or a fire_charge";
        }
        target = new BlockPos(x, y, z);
        ServerLevel level = p.level();
        BlockState s = level.getBlockState(target);
        String id = blockId(p, target);
        if (s.is(Blocks.TNT)) {
            kind = Kind.TNT;
        } else if (s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)) {
            kind = Kind.CAMPFIRE;
            if (CampfireBlock.isLitCampfire(s)) return "the " + id + " at " + coords(target) + " is already lit";
            if (!CampfireBlock.canLight(s)) return "the " + id + " at " + coords(target) + " can't be lit (it is under water)";
        } else if (s.is(BlockTags.CANDLES) && s.hasProperty(BlockStateProperties.LIT)) {
            kind = Kind.CANDLE;
            if (s.getValue(BlockStateProperties.LIT)) return "the " + id + " at " + coords(target) + " is already lit";
        } else {
            kind = Kind.OTHER;
        }
        if (kind != Kind.TNT && kind != Kind.CAMPFIRE) {
            String owner = owner(p, level);
            if (owner != null) return "that block belongs to " + owner;
        }
        if (kind == Kind.OTHER && !level.getBlockState(target.above()).isAir()) return "no room for fire above " + coords(target);
        return null;
    }

    private String owner(AiPlayer p, ServerLevel level) {
        PlacedBlocks placed = PlacedBlocks.of(level.getServer());
        if (!placed.isPlacedByOther(level, target, p.getScoreboardName())) return null;
        Optional<PlacementStore.Placement> at = placed.at(level, target);
        return at.map(PlacementStore.Placement::placer).orElse("someone else");
    }

    @Override
    protected ActionStatus arrived(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (primed) return flee(ctx);
        InventoryOps.selectBest(p, s -> s.is(Items.FLINT_AND_STEEL) ? 2 : s.is(Items.FIRE_CHARGE) ? 1 : 0);
        BlockUse.Result r = kind == Kind.OTHER ? BlockUse.clickFace(p, target, Direction.UP) : BlockUse.click(p, target);
        switch (r) {
            case OUT_OF_REACH:
                return ActionStatus.failed("too far from " + coords(target) + " to light it");
            case NOT_VISIBLE:
                return ActionStatus.failed("can't see " + (kind == Kind.OTHER ? "the top of " : "") + coords(target));
            default:
                break;
        }
        BlockState now = p.level().getBlockState(target);
        switch (kind) {
            case TNT:
                if (now.is(Blocks.TNT)) return ActionStatus.failed("the tnt at " + coords(target) + " did not light");
                done = "primed tnt at " + coords(target);
                primed = true; // stay for the flee, then report
                return flee(ctx);
            case CAMPFIRE:
                if (!CampfireBlock.isLitCampfire(now)) return ActionStatus.failed("the campfire did not light");
                done = "lit the campfire";
                return ActionStatus.DONE;
            case CANDLE:
                if (!now.hasProperty(BlockStateProperties.LIT) || !now.getValue(BlockStateProperties.LIT)) {
                    return ActionStatus.failed("the candle did not light");
                }
                done = "lit the candle";
                return ActionStatus.DONE;
            default:
                if (!(p.level().getBlockState(target.above()).getBlock() instanceof BaseFireBlock)) {
                    return ActionStatus.failed("no fire could be set on top of " + coords(target));
                }
                done = "set fire on top of " + coords(target);
                return ActionStatus.DONE;
        }
    }

    /** A lit fuse is four seconds: walk out of the blast before reporting. */
    private ActionStatus flee(BotContext ctx) {
        Vec3 here = ctx.position();
        Vec3 tnt = Vec3.atCenterOf(target);
        double dx = here.x - tnt.x, dz = here.z - tnt.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d >= SAFE_DISTANCE || ++fled > FLEE_TICKS) {
            ctx.navigator().stop();
            return ActionStatus.DONE;
        }
        if (fled == 1 || ctx.navigator().status().state() != NavStatus.State.RUNNING) {
            if (d < 0.1) {
                dx = 1;
                dz = 0;
                d = 1;
            }
            ctx.navigator().goTo(new ColumnGoal(tnt.x + dx / d * (SAFE_DISTANCE + 3), tnt.z + dz / d * (SAFE_DISTANCE + 3), 2));
        }
        return ActionStatus.RUNNING;
    }

    @Override
    protected String label() {
        return "ignite " + x + " " + y + " " + z;
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }
}
