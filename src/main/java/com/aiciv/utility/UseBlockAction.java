package com.aiciv.utility;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Presses a button, flips a lever, opens or closes a door, trapdoor or gate, or rings a bell. */
public final class UseBlockAction implements Action {
    static final double REACH = 4.0;
    static final String NOT_ALLOWED = "use_block works on buttons, levers, wooden doors, trapdoors, fence gates and bells; use look_in_chest for chests";

    private final BlockPos pos;
    private Approach approach;
    private String done;

    public UseBlockAction(int x, int y, int z) {
        this.pos = new BlockPos(x, y, z);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        BlockState before = p.level().getBlockState(pos);
        if (approach == null) {
            if (!allowed(before)) return ActionStatus.failed(NOT_ALLOWED);
            approach = Approach.block(pos, REACH);
        }
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        if (!allowed(before)) return ActionStatus.failed(NOT_ALLOWED);
        BlockUse.withFreeHand(p);
        BlockUse.Result r = BlockUse.click(p, pos);
        switch (r) {
            case OUT_OF_REACH: return ActionStatus.failed("too far from " + where() + " to use it");
            case NOT_VISIBLE: return ActionStatus.failed("can't see " + where());
            case NO_EFFECT: return ActionStatus.failed("nothing happened");
            default: break;
        }
        done = summary(before, p.level().getBlockState(pos));
        return ActionStatus.DONE;
    }

    static boolean allowed(BlockState s) {
        return s.is(BlockTags.BUTTONS) || s.is(Blocks.LEVER) || s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS)
                || s.is(BlockTags.FENCE_GATES) || s.is(Blocks.BELL);
    }

    private String summary(BlockState before, BlockState after) {
        String id = BuiltInRegistries.BLOCK.getKey(before.getBlock()).getPath();
        String at = where(id);
        if (before.is(BlockTags.BUTTONS)) return "pressed " + at;
        if (before.is(Blocks.LEVER)) {
            boolean on = after.hasProperty(BlockStateProperties.POWERED) && after.getValue(BlockStateProperties.POWERED);
            return "flipped " + at + " (now " + (on ? "on" : "off") + ")";
        }
        if (before.is(Blocks.BELL)) return "rang " + at;
        boolean wasOpen = before.hasProperty(BlockStateProperties.OPEN) && before.getValue(BlockStateProperties.OPEN);
        return (wasOpen ? "closed " : "opened ") + at;
    }

    private String where() {
        return where("block");
    }

    private String where(String id) {
        return id + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (approach != null) approach = Approach.block(pos, REACH);
    }

    @Override
    public String describe() {
        return done != null ? done : "use_block " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Override
    public int timeoutTicks() {
        return 30 * 20;
    }
}
