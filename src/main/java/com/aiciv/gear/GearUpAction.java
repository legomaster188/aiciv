package com.aiciv.gear;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.CraftAction;
import com.aiciv.actions.EquipAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.craft.CraftExecutor;
import com.aiciv.craft.CraftPlanner;
import com.aiciv.craft.RecipeBook;
import com.aiciv.gear.GearPlan.Job;
import com.aiciv.gear.GearPlan.Slot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Items;

/**
 * Wears the best armor the bot has or can make, one piece at a time through {@link EquipAction} and {@link CraftAction},
 * then a shield in the off hand. Each step is planned from what the bot holds right then, so materials spent on one piece
 * are not promised to the next.
 */
public final class GearUpAction implements Action {
    public static final String SHIELD = "shield";
    static final int TIMEOUT_TICKS = 5 * 60 * 20;

    private final List<String> materials;
    private final boolean wantShield;
    private final Set<String> failed = new HashSet<>();
    private final List<String> problems = new ArrayList<>();
    private Action sub;
    private String subItem;
    private boolean subCrafts;
    private int subTicks;
    private int crafted;
    private int changes;
    private String doneSummary;

    /** {@code materials}: the armor materials to craft, best first (see {@link GearPlan#AUTO}). */
    public GearUpAction(List<String> materials, boolean wantShield) {
        this.materials = List.copyOf(materials);
        this.wantShield = wantShield;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (sub != null) {
            ActionStatus status = sub.tick(ctx);
            subTicks++;
            if (status.state() == ActionStatus.State.RUNNING && subTicks < sub.timeoutTicks()) return ActionStatus.RUNNING;
            if (status.state() == ActionStatus.State.DONE) {
                changes++;
                if (subCrafts) crafted++;
            } else {
                if (status.state() == ActionStatus.State.RUNNING) sub.cancel(ctx);
                failed.add(subItem);
                problems.add(status.state() == ActionStatus.State.FAILED ? status.reason() : "timed out making " + subItem);
            }
            sub = null;
        }
        Job job = nextJob(ctx.player());
        if (job == null) return finish(ctx.player());
        subItem = job.item();
        subCrafts = job instanceof GearPlan.Craft;
        sub = subCrafts ? new CraftAction(subItem, 1) : new EquipAction(subItem);
        subTicks = 0;
        return ActionStatus.RUNNING;
    }

    private Job nextJob(AiPlayer p) {
        Map<String, Integer> inventory = CraftExecutor.inventoryCounts(p);
        Job job = GearPlan.next(Armor.worn(p), inventory, materials, id -> canMake(p, id), Armor::score, failed);
        if (job != null || !wantShield || failed.contains(SHIELD) || !p.getOffhandItem().isEmpty()) return job;
        // The off hand is empty here, so a shield CraftExecutor counts is in the pack.
        if (inventory.getOrDefault(SHIELD, 0) > 0) return new GearPlan.Equip(SHIELD);
        return canMake(p, SHIELD) ? new GearPlan.Craft(SHIELD) : null;
    }

    private static boolean canMake(AiPlayer p, String id) {
        return CraftPlanner.canMake(id, 1, CraftExecutor.inventoryCounts(p), RecipeBook.load(p.level()), CraftAction.tableNearby(p));
    }

    private ActionStatus finish(AiPlayer p) {
        Map<Slot, String> worn = Armor.worn(p);
        boolean shield = p.getOffhandItem().is(Items.SHIELD);
        if (changes == 0 && worn.isEmpty() && !shield) {
            String why = problems.isEmpty() ? "" : " (" + String.join("; ", problems) + ")";
            return ActionStatus.failed("can't make any armor: need leather, iron_ingot, gold_ingot or diamond" + why);
        }
        List<String> names = new ArrayList<>();
        for (Slot slot : new Slot[] {Slot.HEAD, Slot.CHEST, Slot.LEGS, Slot.FEET}) {
            if (worn.containsKey(slot)) names.add(worn.get(slot));
        }
        StringBuilder text = new StringBuilder(changes == 0 ? "already wearing " : "wearing ");
        text.append(names.isEmpty() ? "no armor" : String.join(", ", names));
        if (shield) text.append(" + shield");
        if (crafted > 0) text.append(" (crafted ").append(crafted).append(')');
        if (changes == 0) text.append(" - nothing better to put on or make");
        doneSummary = text.toString();
        return ActionStatus.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (sub != null) sub.cancel(ctx);
        sub = null;
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (sub != null) sub.interrupted(ctx);
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "gear up";
    }

    @Override
    public int timeoutTicks() {
        return TIMEOUT_TICKS;
    }
}
