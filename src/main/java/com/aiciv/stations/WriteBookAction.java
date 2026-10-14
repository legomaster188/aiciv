package com.aiciv.stations;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * Writes pages into a held book and quill, and signs it when given a title, by sending the game's own edit-book packet
 * from the hotbar slot. The server applies the edit a tick or more later, so the action waits to see the result.
 */
public final class WriteBookAction implements Action {
    static final int WAIT_TICKS = 10;

    private final List<String> pages;
    private final String title;
    private int slot = -1;
    private int waited;
    private String done;

    /** {@code pages} are at most 1024 characters each; {@code titleOrNull} 1..32 characters. */
    public WriteBookAction(List<String> pages, String titleOrNull) {
        this.pages = List.copyOf(pages);
        this.title = titleOrNull == null || titleOrNull.isBlank() ? null : titleOrNull.trim();
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (slot < 0) {
            if (InventoryOps.count(p, s -> s.is(Items.WRITABLE_BOOK)) == 0) return ActionStatus.failed("needs a writable_book (a book and quill)");
            if (!ItemUse.select(p, s -> s.is(Items.WRITABLE_BOOK))) return ActionStatus.failed("could not hold the writable_book");
            slot = p.getInventory().getSelectedSlot();
            p.connection.handleEditBook(new ServerboundEditBookPacket(slot, pages, Optional.ofNullable(title)));
            return ActionStatus.RUNNING;
        }
        ItemStack book = p.getInventory().getItem(slot);
        if (title != null && book.is(Items.WRITTEN_BOOK)) {
            WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (content != null && content.title().raw().equals(title)) {
                done = "signed the book \"" + title + "\"";
                return ActionStatus.DONE;
            }
        }
        if (title == null && book.is(Items.WRITABLE_BOOK)) {
            WritableBookContent content = book.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (content != null && content.getPages(false).toList().equals(pages)) {
                done = "wrote " + pages.size() + " page" + (pages.size() == 1 ? "" : "s");
                return ActionStatus.DONE;
            }
        }
        if (++waited >= WAIT_TICKS) return ActionStatus.failed("the book did not take the text");
        return ActionStatus.RUNNING;
    }

    @Override
    public String describe() {
        return done != null ? done : "write " + pages.size() + " page" + (pages.size() == 1 ? "" : "s") + (title != null ? " and sign it \"" + title + "\"" : "");
    }

    @Override
    public int timeoutTicks() {
        return 40;
    }
}
