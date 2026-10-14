package com.aiciv.nether;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.building.BuildAction;
import com.aiciv.building.Facing;
import com.aiciv.dimension.Portals;
import com.aiciv.nav.Pos;
import com.aiciv.world.InventoryOps;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Builds a nether portal frame from obsidian with the building action, then lights it if the bot holds something to light it with. */
public final class BuildPortalAction implements Action {
    private static final int LIGHT_ALLOWANCE = 600;
    private static final int BUILD_TIMEOUT = Math.min(24000, 600 + 40 * 14);

    private enum Stage { START, BUILD, LIGHT }

    private final BlockPos givenOrigin;
    private final Facing givenFacing;
    private Stage stage = Stage.START;
    private Action child;
    private BlockPos origin;
    private Facing facing;
    private String summary;

    public BuildPortalAction(BlockPos originOrNull, Facing facingOrNull) {
        this.givenOrigin = originOrNull;
        this.givenFacing = facingOrNull;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (stage == Stage.START) {
            ActionStatus problem = start(p);
            if (problem != null) return problem;
        }
        ActionStatus s = child.tick(ctx);
        switch (s.state()) {
            case RUNNING:
                return s;
            case FAILED:
                return s;
            default:
                return stage == Stage.BUILD ? built(ctx, p) : finish(true);
        }
    }

    private ActionStatus start(AiPlayer p) {
        Map<String, Integer> inventory = inventory(p);
        int obsidian = inventory.getOrDefault("obsidian", 0);
        if (obsidian < PortalFrame.OBSIDIAN) return ActionStatus.failed("needs 10 obsidian (you have " + obsidian + ")");
        String corner = PortalFrame.cornerBlock(inventory);
        if (corner == null) return ActionStatus.failed("needs 4 more obsidian, or 4 cobblestone, dirt or planks for the corners");
        facing = givenFacing != null ? givenFacing : Facing.of(p.getDirection());
        if (givenOrigin != null) {
            origin = givenOrigin;
        } else {
            origin = defaultOrigin(p, facing);
        }
        child = new BuildAction(structureName(Portals.key(p.level()), origin), PortalFrame.blueprint(corner), origin, facing, "portal");
        stage = Stage.BUILD;
        return null;
    }

    private ActionStatus built(BotContext ctx, AiPlayer p) {
        if (InventoryOps.count(p, LightPortalAction.IGNITER) == 0) {
            ctx.event("frame built at " + LightPortalAction.coords(origin) + "; light it with light_portal");
            return finish(false);
        }
        Pos base = PortalFrame.frameCells(new Pos(origin.getX(), origin.getY(), origin.getZ()), facing).get(0);
        child = new LightPortalAction(new BlockPos(base.x(), base.y(), base.z()));
        stage = Stage.LIGHT;
        return ActionStatus.RUNNING;
    }

    private ActionStatus finish(boolean lit) {
        summary = "built a nether portal at " + LightPortalAction.coords(origin) + (lit ? " (lit)" : " (not lit)");
        return ActionStatus.DONE;
    }

    /** The building action's own default: two blocks in front, the four-wide frame centred. */
    static BlockPos defaultOrigin(AiPlayer p, Facing facing) {
        BlockPos feet = p.blockPosition();
        return new BlockPos(feet.getX() + 2 * facing.forwardX() - 2 * facing.rightX(), feet.getY(),
                feet.getZ() + 2 * facing.forwardZ() - 2 * facing.rightZ());
    }

    /** Every item the bot carries, by id without namespace. */
    static Map<String, Integer> inventory(AiPlayer p) {
        Map<String, Integer> out = new HashMap<>();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
        }
        return out;
    }

    /**
     * The name the finished frame is listed under: {@code portal_<dimension>_<x>_<y>_<z>} of its bottom left corner,
     * minus signs written m, so frames above each other or at the same spot in another dimension get names of their own.
     */
    public static String structureName(String dimension, BlockPos origin) {
        return "portal_" + place(dimension, origin);
    }

    /** {@code <dimension>_<x>_<y>_<z>}, e.g. {@code the_nether_m3_70_12}. */
    static String place(String dimension, BlockPos origin) {
        return Portals.shortName(dimension) + "_" + coordinate(origin.getX()) + "_" + coordinate(origin.getY()) + "_" + coordinate(origin.getZ());
    }

    static String coordinate(int v) {
        return v < 0 ? "m" + (-v) : String.valueOf(v);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (child != null) child.interrupted(ctx);
    }

    @Override
    public void cancel(BotContext ctx) {
        if (child != null) child.cancel(ctx);
    }

    @Override
    public String describe() {
        return summary != null ? summary : "build portal";
    }

    @Override
    public int timeoutTicks() {
        return BUILD_TIMEOUT + LIGHT_ALLOWANCE;
    }
}
