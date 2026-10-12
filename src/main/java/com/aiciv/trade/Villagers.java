package com.aiciv.trade;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.EntityUse;
import com.aiciv.world.Finder;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;

/** Finding villagers and opening their trading menu the way a player does (right-click). */
public final class Villagers {
    public static final double RADIUS = 32.0;

    private Villagers() {}

    /** The villager's profession as a lower-case word ("farmer"); "none" without one. */
    public static String profession(Villager v) {
        Optional<ResourceKey<VillagerProfession>> key = v.getVillagerData().profession().unwrapKey();
        return key.map(k -> k.identifier().getPath()).orElse("none");
    }

    /** True for professions that actually trade: not unemployed, not a nitwit. */
    static boolean trades(String profession) {
        return !profession.equals("none") && !profession.equals("nitwit");
    }

    /** "Farmers" and " farmer " both mean "farmer"; null for nothing given. */
    static String clean(String profession) {
        if (profession == null || profession.isBlank()) return null;
        String p = profession.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (p.startsWith("minecraft:")) p = p.substring("minecraft:".length());
        return p;
    }

    /** The nearest adult villager with a trade, of the given profession when one is named, within {@code radius}. */
    public static Optional<Villager> find(AiPlayer p, String professionOrNull, double radius) {
        String want = clean(professionOrNull);
        String singular = want != null && want.endsWith("s") ? want.substring(0, want.length() - 1) : want;
        return Finder.nearestEntity(p, Villager.class, v -> {
            if (v.isBaby()) return false;
            String prof = profession(v);
            if (!trades(prof)) return false;
            return want == null || prof.equals(want) || prof.equals(singular);
        }, radius);
    }

    /** Right-clicks the villager; its trading menu if that opened, else null (and whatever opened is closed). */
    public static MerchantMenu open(AiPlayer p, Villager v) {
        EntityUse.interact(p, v, InteractionHand.MAIN_HAND);
        if (p.containerMenu instanceof MerchantMenu menu) return menu;
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        return null;
    }
}
