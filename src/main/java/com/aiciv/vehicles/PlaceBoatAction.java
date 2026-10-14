package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Puts a boat or raft from the inventory on open water the bot can see. */
public final class PlaceBoatAction implements Action {
    static final int RADIUS = 4;
    /** A boat 1.375 wide next to a bot 0.6 wide: closer than this and the boat would be placed inside the bot. */
    private static final double MIN_DISTANCE = 1.8;
    private final Set<BlockPos> tried = new HashSet<>();
    private String result;

    static boolean isBoatItem(ItemStack s) {
        if (s.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return id.endsWith("_boat") || id.endsWith("_raft");
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!InventoryOps.selectBest(p, s -> isBoatItem(s) ? 1 : 0)) return ActionStatus.failed("you carry no boat or raft");
        BlockPos target = null;
        for (BlockPos w : openWater(p)) {
            if (!tried.contains(w)) {
                target = w;
                break;
            }
        }
        if (target == null) return ActionStatus.failed(tried.isEmpty() ? "no open water within 4 blocks" : "could not put the boat on the water");
        tried.add(target);
        // Aim a little under the surface so the ray meets it; vanilla places the boat where the ray hits the water.
        Vec3 aim = new Vec3(target.getX() + 0.5, target.getY() + 0.8, target.getZ() + 0.5);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
        String item = BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath();
        AABB around = new AABB(aim, aim).inflate(3);
        int before = p.level().getEntitiesOfClass(AbstractBoat.class, around).size();
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (p.level().getEntitiesOfClass(AbstractBoat.class, around).size() <= before) return ActionStatus.RUNNING;
        result = "placed an " + item + " at " + target.getX() + " " + target.getY() + " " + target.getZ();
        return ActionStatus.DONE;
    }

    /**
     * Water sources near the eyes with a water source on every side (a boat is wider than a block) and air above, whose
     * surface the eyes can see, nearest first. Only what a player looking at the lake would pick out.
     */
    private static List<BlockPos> openWater(AiPlayer p) {
        ServerLevel level = p.level();
        Vec3 eyes = p.getEyePosition();
        BlockPos base = BlockPos.containing(eyes);
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int y = base.getY() + 1; y >= base.getY() - RADIUS; y--) {
                    at.set(base.getX() + dx, y, base.getZ() + dz);
                    if (!level.isLoaded(at) || !isSource(level.getBlockState(at)) || !level.getBlockState(at.above()).isAir()) continue;
                    Vec3 aim = new Vec3(at.getX() + 0.5, at.getY() + 0.8, at.getZ() + 0.5);
                    double horizontal = Math.hypot(aim.x - eyes.x, aim.z - eyes.z);
                    if (horizontal > RADIUS || horizontal < MIN_DISTANCE || aim.distanceTo(eyes) > p.blockInteractionRange()) continue;
                    if (!surroundedByWater(level, at) || !clearSight(level, p, eyes, aim)) continue;
                    out.add(at.immutable());
                    break;
                }
            }
        }
        out.sort(Comparator.comparingDouble(w -> Vec3.atCenterOf(w).distanceToSqr(eyes)));
        return out;
    }

    private static boolean isSource(BlockState s) {
        return s.is(Blocks.WATER) && s.getFluidState().isSource();
    }

    private static boolean surroundedByWater(ServerLevel level, BlockPos pos) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (!isSource(level.getBlockState(pos.offset(x, 0, z)))) return false;
            }
        }
        return true;
    }

    private static boolean clearSight(ServerLevel level, AiPlayer p, Vec3 eyes, Vec3 aim) {
        return level.clip(new ClipContext(eyes, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    @Override
    public String describe() {
        return result != null ? result : "place a boat";
    }

    @Override
    public int timeoutTicks() {
        return 200;
    }
}
