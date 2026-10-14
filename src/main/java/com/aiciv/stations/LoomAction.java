package com.aiciv.stations;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import com.aiciv.world.MenuOps;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPattern;

/** Adds one pattern layer to a held banner at a loom, with a dye (and a pattern item for the special patterns). */
public final class LoomAction extends StationAction<LoomMenu> {
    private static final int OFFERED = 12;

    private final String pattern;
    private final String key;
    private final DyeColor color;
    private final DyeColor bannerColor;

    /** {@code color} and {@code bannerOrNull} are dye colour names; the factory has checked them. */
    public LoomAction(String pattern, String color, String bannerOrNull) {
        super("loom", LoomMenu.class);
        this.pattern = pattern.trim();
        this.key = StationNames.patternKey(pattern);
        this.color = DyeColor.byName(StationNames.normalise(color), null);
        this.bannerColor = bannerOrNull == null || bannerOrNull.isBlank() ? null : DyeColor.byName(StationNames.normalise(bannerOrNull), null);
        if (this.color == null) throw new IllegalArgumentException("no dye colour " + color);
    }

    private Predicate<ItemStack> banner() {
        return s -> !s.isEmpty() && (bannerColor != null ? s.is(Items.BANNER.pick(bannerColor)) : Items.BANNER.asList().stream().anyMatch(s::is));
    }

    private Predicate<ItemStack> dye() {
        return s -> s.is(Items.DYE.pick(color));
    }

    @Override
    protected String missing(AiPlayer p) {
        List<String> lacking = new ArrayList<>();
        if (InventoryOps.count(p, banner()) == 0) lacking.add(bannerColor == null ? "a banner" : bannerColor.getName() + "_banner");
        if (InventoryOps.count(p, dye()) == 0) lacking.add(color.getName() + "_dye");
        return lacking.isEmpty() ? null : "needs " + String.join(", ", lacking);
    }

    private static String keyOf(Holder<BannerPattern> h) {
        return h.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
    }

    private int indexOf(LoomMenu menu) {
        List<Holder<BannerPattern>> offered = menu.getSelectablePatterns();
        for (int i = 0; i < offered.size(); i++) if (keyOf(offered.get(i)).equals(key)) return i;
        return -1;
    }

    @Override
    protected ActionStatus work(BotContext ctx, AiPlayer p, LoomMenu menu) {
        if (MenuOps.putInto(p, menu, banner(), menu.getBannerSlot().index, 1) == 0) return ActionStatus.failed("could not put the banner in the loom");
        if (MenuOps.putInto(p, menu, dye(), menu.getDyeSlot().index, 1) == 0) return ActionStatus.failed("could not put the " + color.getName() + "_dye in the loom");
        int index = indexOf(menu);
        if (index < 0) {
            List<String> offered = menu.getSelectablePatterns().stream().map(LoomAction::keyOf).limit(OFFERED).toList();
            ItemStack patternItem = patternItemFor(p);
            if (patternItem == null || MenuOps.putInto(p, menu, s -> ItemStack.isSameItem(s, patternItem), menu.getPatternSlot().index, 1) == 0
                    || (index = indexOf(menu)) < 0) {
                return ActionStatus.failed("the loom has no pattern " + pattern + "; it offers: " + String.join(", ", offered));
            }
        }
        if (!menu.clickMenuButton(p, index)) return ActionStatus.failed("the loom refused pattern " + pattern);
        if (menu.getResultSlot().getItem().isEmpty()) return ActionStatus.failed("the loom shows no result (a banner holds at most 6 patterns)");
        ItemStack taken = MenuOps.takeResult(p, menu, menu.getResultSlot().index);
        if (taken.isEmpty()) return ActionStatus.failed("could not take the banner from the loom");
        done = "made a " + ItemNames.id(taken) + " with " + color.getName() + " " + key;
        return ActionStatus.DONE;
    }

    /** A held banner-pattern item (a creeper charge...) that unlocks this pattern; null when there is none. */
    private ItemStack patternItemFor(AiPlayer p) {
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            HolderSet<BannerPattern> provided = s.isEmpty() ? null : s.get(DataComponents.PROVIDES_BANNER_PATTERNS);
            if (provided != null && provided.stream().anyMatch(h -> keyOf(h).equals(key))) return s.copy();
        }
        return null;
    }

    @Override
    public String describe() {
        return done != null ? done : "loom " + pattern + " in " + color.getName();
    }
}
