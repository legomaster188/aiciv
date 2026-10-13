package com.aiciv.trade;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.Attachments;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.Pos;
import com.aiciv.world.Finder;
import com.aiciv.world.MenuOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.Vec3;

/**
 * Finds a villager, walks up to it and opens its trading menu; the subclass then works with the open menu. The menu is
 * always closed again (and anything on the cursor returned) however the action ends.
 */
abstract class VillagerAction implements Action {
    static final double STAND_RADIUS = 3.0;
    /** How far a remembered villager may have wandered from where it was seen and still count as found. */
    static final double WANDER = 3.0;
    private static final int MAX_OPEN_TRIES = 3;

    private final String wanted;
    private final BlockPos seenAt;
    private Approach approach;
    private int opens;
    protected Villager villager;
    protected String profession;
    protected MerchantMenu menu;

    VillagerAction(String wanted) {
        this(wanted, null);
    }

    /** @param seenAtOrNull where the villager to use was seen; null means the nearest one of {@code wanted} */
    VillagerAction(String wanted, BlockPos seenAtOrNull) {
        this.wanted = Villagers.clean(wanted);
        this.seenAt = seenAtOrNull;
    }

    @Override
    public final ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (menu != null) return withMenu(ctx, p);
        if (villager == null) {
            if (seenAt != null) {
                villager = remembered(ctx, p);
                if (villager == null) {
                    return ActionStatus.failed("no " + (wanted == null ? "villager" : wanted) + " at " + seenAt.getX() + " " + seenAt.getY() + " " + seenAt.getZ()
                            + " any more");
                }
            } else {
                villager = Villagers.find(p, wanted, Villagers.RADIUS).orElse(null);
            }
            if (villager == null) return ActionStatus.failed(wanted == null ? "no villager nearby" : "no " + wanted + " nearby");
            profession = Villagers.profession(villager);
            approach = Approach.entity(villager, STAND_RADIUS);
        }
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        menu = Villagers.open(p, villager);
        if (menu == null) {
            // Still out of reach or sight (it may have wandered): walk up again, a few times.
            boolean near = p.isWithinEntityInteractionRange(villager, 0.0) && p.hasLineOfSight(villager);
            if (!near && ++opens < MAX_OPEN_TRIES) {
                approach = Approach.entity(villager, STAND_RADIUS);
                return ActionStatus.RUNNING;
            }
            return ActionStatus.failed("the " + profession + " won't trade right now (busy, asleep or a baby)");
        }
        return withMenu(ctx, p);
    }

    /**
     * The villager seen at {@link #seenAt}: the one remembered there if it is still alive within range, else one of the
     * wanted profession within {@value #WANDER} blocks of that spot. Never some other villager further off.
     */
    private Villager remembered(BotContext ctx, AiPlayer p) {
        VillagerMemory memory = ctx.attachments() == null ? null : ctx.attachments().find(VillagerMemory.class).orElse(null);
        if (memory != null) {
            memory.enterDimension(p.level().dimension().identifier().toString());
            Optional<UUID> id = memory.idAt(new Pos(seenAt.getX(), seenAt.getY(), seenAt.getZ()));
            if (id.isPresent()) {
                return p.level().getEntity(id.get()) instanceof Villager v && v.isAlive() && v.distanceTo(p) <= Villagers.RADIUS ? v : null;
            }
        }
        Vec3 spot = Vec3.atBottomCenterOf(seenAt);
        return Finder.nearestEntity(p, Villager.class, v -> v.position().distanceTo(spot) <= WANDER && Villagers.matches(v, wanted), Villagers.RADIUS)
                .orElse(null);
    }

    /** Ticked while this action has the trading menu open; must close it (via {@link #closeMenu}) when it stops. */
    abstract ActionStatus withMenu(BotContext ctx, AiPlayer p);

    final void closeMenu(AiPlayer p) {
        if (menu == null) return;
        MenuOps.close(p);
        menu = null;
    }

    /** The menu's offers as views, remembered for the bot's snapshot. */
    final List<OfferView> readOffers(BotContext ctx) {
        List<OfferView> views = new ArrayList<>();
        List<MerchantOffer> offers = menu.getOffers();
        for (int i = 0; i < offers.size(); i++) views.add(OfferView.of(i, offers.get(i)));
        Attachments memory = ctx.attachments();
        if (memory != null) {
            BlockPos at = villager.blockPosition();
            VillagerMemory villagers = memory.get(VillagerMemory.class, VillagerMemory::new);
            villagers.enterDimension(villager.level().dimension().identifier().toString());
            villagers.seen(villager.getUUID(), profession, new Pos(at.getX(), at.getY(), at.getZ()), views, villager.level().getGameTime());
        }
        return views;
    }

    final String where() {
        BlockPos at = villager.blockPosition();
        return profession + " at " + at.getX() + " " + at.getY() + " " + at.getZ();
    }

    static String joined(List<OfferView> offers, int max) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < Math.min(max, offers.size()); i++) texts.add(offers.get(i).text());
        return String.join("; ", texts);
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        AiPlayer p = ctx.player();
        if (p != null && menu != null) closeMenu(p);
    }

    @Override
    public void interrupted(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (p != null) closeMenu(p);
        opens = 0;
        if (villager != null) approach = Approach.entity(villager, STAND_RADIUS); // walk up again afterwards
    }
}
