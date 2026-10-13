package com.aiciv.storage;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.PlaceAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.ItemNames;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.FilteredText;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;

/** Places a held sign (at a cell, else beside the bot) and writes the text on its front. */
public final class SignAction implements Action {
    private final List<String> lines;
    private final BlockPos given;
    private PlaceAction placing;
    private String doneSummary;

    /** @param orNull the cell to put the sign in; null means a free floor cell beside the bot */
    public SignAction(String text, BlockPos orNull) {
        this.lines = SignLines.wrap(text);
        this.given = orNull;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (lines.isEmpty()) return ActionStatus.failed("nothing to write on the sign");
        BlockPos cell;
        if (given == null) {
            if (!holdsSign(p)) return ActionStatus.failed("no sign in inventory");
            cell = BlockPlacer.placeNearby(p, s -> s.is(ItemTags.SIGNS));
            if (cell == null) return ActionStatus.failed("no room to place a sign here");
        } else {
            if (placing == null) {
                ItemStack sign = firstSign(p);
                if (sign == null) return ActionStatus.failed("no sign in inventory");
                placing = new PlaceAction(ItemNames.id(sign), given.getX(), given.getY(), given.getZ());
            }
            ActionStatus s = placing.tick(ctx);
            if (s.state() == ActionStatus.State.RUNNING) return s;
            if (s.state() == ActionStatus.State.FAILED) return s;
            cell = given;
        }
        BlockEntity be = p.level().getBlockEntity(cell);
        if (!(be instanceof SignBlockEntity sign)) return ActionStatus.failed("the sign at " + cell.getX() + " " + cell.getY() + " " + cell.getZ() + " has no text to edit");
        sign.updateSignText(p, SignTextSlot.FRONT, lines.stream().map(FilteredText::passThrough).toList());
        sign.setAllowedPlayerEditor(null); // placing a sign opens its editor for the placer; nobody is typing
        if (!written(sign)) return ActionStatus.failed("the sign would not take the text");
        doneSummary = "wrote \"" + String.join(" / ", lines) + "\" on a sign at " + cell.getX() + " " + cell.getY() + " " + cell.getZ();
        return ActionStatus.DONE;
    }

    private boolean written(SignBlockEntity sign) {
        List<String> shown = new ArrayList<>();
        for (Component c : sign.getText(SignTextSlot.FRONT).getMessages(false)) shown.add(c.getString());
        while (!shown.isEmpty() && shown.get(shown.size() - 1).isEmpty()) shown.remove(shown.size() - 1);
        return shown.equals(lines);
    }

    private static boolean holdsSign(AiPlayer p) {
        return firstSign(p) != null;
    }

    private static ItemStack firstSign(AiPlayer p) {
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) if (!s.isEmpty() && s.is(ItemTags.SIGNS)) return s;
        return null;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (placing != null) placing.cancel(ctx);
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "write a sign";
    }

    @Override
    public int timeoutTicks() {
        return 30 * 20;
    }
}
