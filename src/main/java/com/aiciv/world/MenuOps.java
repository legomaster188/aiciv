package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Moves items through an open container menu with the clicks a player makes (shift-click, pick up, right-click one
 * item), never by setting another container's slots. Nothing is ever left on the cursor.
 */
public final class MenuOps {
    private static final int PRIMARY = 0, SECONDARY = 1;

    private MenuOps() {}

    /** Right-clicks the block; the menu that opened if it is a {@code type}, else null (and whatever opened is closed). */
    public static <M extends AbstractContainerMenu> M open(AiPlayer p, BlockPos pos, Class<M> type) {
        BlockUse.click(p, pos);
        if (p.containerMenu != p.inventoryMenu && type.isInstance(p.containerMenu)) return type.cast(p.containerMenu);
        close(p);
        return null;
    }

    /** The menu's slots that are not the player's own inventory (a chest's 27, a furnace's 3...). */
    public static List<Slot> containerSlots(AbstractContainerMenu menu) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : menu.slots) if (!(s.container instanceof Inventory)) out.add(s);
        return out;
    }

    /** The menu slot showing the player's inventory index {@code inventoryIndex} (0-8 hotbar, 9-35 main). */
    public static OptionalInt inventorySlot(AbstractContainerMenu menu, AiPlayer p, int inventoryIndex) {
        return menu.findSlot(p.getInventory(), inventoryIndex);
    }

    /** Moves up to {@code max} matching items from the inventory into the container. Returns how many moved. */
    public static int moveIn(AiPlayer p, AbstractContainerMenu menu, Predicate<ItemStack> match, int max) {
        int moved = 0;
        boolean progress = true;
        while (moved < max && progress) {
            progress = false;
            for (int i = 0; i < p.getInventory().getNonEquipmentItems().size() && moved < max; i++) {
                ItemStack stack = p.getInventory().getItem(i);
                if (stack.isEmpty() || !match.test(stack)) continue;
                OptionalInt slot = inventorySlot(menu, p, i);
                if (slot.isEmpty()) continue;
                int n = transfer(p, menu, slot.getAsInt(), containerSlots(menu), max - moved);
                if (n > 0) {
                    moved += n;
                    progress = true;
                }
            }
        }
        return moved;
    }

    /** Moves up to {@code max} items from container slot {@code slot} (a menu index) into the inventory. */
    public static int moveOut(AiPlayer p, AbstractContainerMenu menu, int slot, int max) {
        List<Slot> inventory = new ArrayList<>();
        for (Slot s : menu.slots) if (s.container == p.getInventory()) inventory.add(s);
        int moved = 0;
        while (moved < max) {
            int n = transfer(p, menu, slot, inventory, max - moved);
            if (n == 0) break;
            moved += n;
        }
        return moved;
    }

    /**
     * Moves up to {@code max} items out of menu slot {@code from} into {@code targets}: a shift-click when the whole stack
     * should go, else pick the stack up, right-click one item at a time into the first target that takes it, and put
     * the rest back. Returns how many moved; 0 when no target has room.
     */
    private static int transfer(AiPlayer p, AbstractContainerMenu menu, int from, List<Slot> targets, int max) {
        Slot source = menu.getSlot(from);
        ItemStack stack = source.getItem();
        if (stack.isEmpty() || max <= 0) return 0;
        int before = stack.getCount();
        if (max >= before) {
            menu.clicked(from, PRIMARY, ContainerInput.QUICK_MOVE, p);
            returnCarried(p, menu);
            return before - source.getItem().getCount();
        }
        menu.clicked(from, PRIMARY, ContainerInput.PICKUP, p);
        int moved = 0;
        while (moved < max && !menu.getCarried().isEmpty()) {
            Slot target = accepting(targets, menu.getCarried());
            if (target == null) break;
            int carried = menu.getCarried().getCount();
            menu.clicked(target.index, SECONDARY, ContainerInput.PICKUP, p);
            if (menu.getCarried().getCount() >= carried) break; // the slot refused it after all
            moved += carried - menu.getCarried().getCount();
        }
        if (!menu.getCarried().isEmpty()) menu.clicked(from, PRIMARY, ContainerInput.PICKUP, p);
        returnCarried(p, menu);
        return moved;
    }

    /**
     * Puts up to {@code count} matching items from the inventory into menu slot {@code slot}: picks up a matching stack,
     * left-clicks it in when all of it should go, else right-clicks one item at a time, and puts the rest back. Never
     * swaps out what the slot holds. Returns how many moved; nothing is left on the cursor.
     */
    public static int putInto(AiPlayer p, AbstractContainerMenu menu, Predicate<ItemStack> match, int slot, int count) {
        returnCarried(p, menu);
        Slot target = menu.getSlot(slot);
        int moved = 0;
        while (moved < count) {
            Slot from = null;
            for (Slot s : menu.slots) {
                if (s.index != slot && s.container == p.getInventory() && !s.getItem().isEmpty() && match.test(s.getItem())) {
                    from = s;
                    break;
                }
            }
            if (from == null) break;
            ItemStack in = target.getItem();
            if (!target.mayPlace(from.getItem()) || (!in.isEmpty() && !ItemStack.isSameItemSameComponents(in, from.getItem()))) break;
            menu.clicked(from.index, PRIMARY, ContainerInput.PICKUP, p);
            int carried = menu.getCarried().getCount();
            int want = count - moved;
            boolean progress;
            if (want >= carried) {
                menu.clicked(slot, PRIMARY, ContainerInput.PICKUP, p);
                int placed = carried - menu.getCarried().getCount();
                moved += placed;
                progress = placed == carried; // a full slot left some on the cursor: no point in another stack
            } else {
                int placed = 0;
                while (placed < want && !menu.getCarried().isEmpty()) {
                    int before = menu.getCarried().getCount();
                    menu.clicked(slot, SECONDARY, ContainerInput.PICKUP, p);
                    if (menu.getCarried().getCount() >= before) break; // the slot refused it
                    placed += before - menu.getCarried().getCount();
                }
                moved += placed;
                progress = placed == want;
            }
            if (!menu.getCarried().isEmpty()) menu.clicked(from.index, PRIMARY, ContainerInput.PICKUP, p);
            returnCarried(p, menu);
            if (!progress) break;
        }
        return moved;
    }

    /**
     * Takes what a result slot (anvil, smithing table, loom...) shows with one click, which runs the menu's own taking
     * (it uses up the inputs), and puts it in the inventory. Returns a copy of what was taken; EMPTY when nothing was.
     */
    public static ItemStack takeResult(AiPlayer p, AbstractContainerMenu menu, int slot) {
        returnCarried(p, menu);
        if (menu.getSlot(slot).getItem().isEmpty()) return ItemStack.EMPTY;
        menu.clicked(slot, PRIMARY, ContainerInput.PICKUP, p);
        ItemStack taken = menu.getCarried().copy();
        returnCarried(p, menu);
        return taken;
    }

    /** A target already holding this item with room, else an empty one that may hold it; null when none. */
    private static Slot accepting(List<Slot> targets, ItemStack carried) {
        Slot empty = null;
        for (Slot s : targets) {
            if (!s.mayPlace(carried)) continue;
            ItemStack in = s.getItem();
            if (in.isEmpty()) {
                if (empty == null) empty = s;
            } else if (ItemStack.isSameItemSameComponents(in, carried) && in.getCount() < s.getMaxStackSize(in)) {
                return s;
            }
        }
        return empty;
    }

    /** Puts anything left on the cursor back into the inventory (or at the feet), so no click ever loses items. */
    private static void returnCarried(AiPlayer p, AbstractContainerMenu menu) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return;
        menu.setCarried(ItemStack.EMPTY);
        InventoryOps.addOrDrop(p, carried);
    }

    /** Returns the carried stack to the inventory, then closes any open container menu. */
    public static void close(AiPlayer p) {
        returnCarried(p, p.containerMenu);
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
    }
}
