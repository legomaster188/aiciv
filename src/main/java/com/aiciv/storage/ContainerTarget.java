package com.aiciv.storage;

import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.Pos;
import com.aiciv.world.Finder;
import com.aiciv.world.ItemNames;
import com.aiciv.world.MenuOps;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Finds a chest (given, else the nearest within 16 blocks), walks up to it until the bot can see it, and opens it with a
 * right-click. Shared by the chest actions, which do their work on the tick after the menu opened.
 */
final class ContainerTarget {
    static final int RADIUS = 16;
    private static final double REACH = 4.0;

    enum State { RUNNING, OPENED, OPEN, FAILED }

    record Step(State state, String reason) {}

    private static final Step RUNNING = new Step(State.RUNNING, "");
    private static final Step OPENED = new Step(State.OPENED, "");
    private static final Step OPEN = new Step(State.OPEN, "");

    private final BlockPos given;
    private BlockPos pos;
    private Approach approach;
    private ChestMenu menu;

    ContainerTarget(BlockPos orNull) {
        this.given = orNull;
    }

    static boolean isContainer(String blockId) {
        return blockId.equals("chest") || blockId.equals("trapped_chest") || blockId.equals("barrel");
    }

    /** RUNNING while walking, OPENED on the tick the menu opens, OPEN on later ticks, FAILED with a reason otherwise. */
    Step tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (menu != null) {
            if (p.containerMenu != menu || !menu.stillValid(p)) {
                close(ctx);
                return failed("the chest closed");
            }
            return OPEN;
        }
        if (pos == null) {
            if (given != null) {
                if (!isContainer(BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(given).getBlock()).getPath())) {
                    return failed("there is no chest at " + given.getX() + " " + given.getY() + " " + given.getZ());
                }
                pos = given;
            } else {
                pos = Finder.nearestBlock(ctx, ContainerTarget::isContainer, RADIUS).orElse(null);
                if (pos == null) return failed("no chest within " + RADIUS + " blocks");
            }
            approach = Approach.block(pos, REACH);
        }
        if (approach != null) {
            Approach.Status s = approach.tick(ctx);
            if (s.state() == Approach.State.RUNNING) return RUNNING;
            approach = null;
            if (s.state() == Approach.State.FAILED) return failed(s.reason());
        }
        menu = MenuOps.open(p, pos, ChestMenu.class);
        if (menu == null) return failed("the chest won't open (is something on top of it?)");
        return OPENED;
    }

    ChestMenu menu() {
        return menu;
    }

    BlockPos pos() {
        return pos;
    }

    String coords() {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** What the chest's own slots hold now, item id to count, most recent slot order. */
    Map<String, Integer> contents() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Slot s : MenuOps.containerSlots(menu)) {
            ItemStack stack = s.getItem();
            if (!stack.isEmpty()) out.merge(ItemNames.id(stack), stack.getCount(), Integer::sum);
        }
        return out;
    }

    /** Writes what the bot just saw inside into its chest memory. */
    void remember(BotContext ctx) {
        if (ctx.attachments() == null || menu == null) return;
        ChestMemory chests = ctx.attachments().get(ChestMemory.class, ChestMemory::new);
        chests.enterDimension(ctx.player().level().dimension().identifier().toString());
        chests.seen(new Pos(pos.getX(), pos.getY(), pos.getZ()), contents(), ctx.player().level().getGameTime());
    }

    /** Stops walking and closes the menu, handing back anything on the cursor. Safe to call at any time. */
    void close(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        approach = null;
        menu = null;
        if (ctx.player() != null) MenuOps.close(ctx.player());
    }

    private Step failed(String reason) {
        return new Step(State.FAILED, reason);
    }
}
