package com.aiciv.dragon;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.Routine;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dragon.DragonTactics.Move;
import com.aiciv.dragon.DragonTactics.Situation;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * The whole fight: crystals first, cages when there is room and health for it, then the dragon itself, one stage at a
 * time, chosen by {@link DragonTactics} from what the bot sees now.
 */
public final class FightDragonRoutine extends Routine {
    static final int MAX_FAILURES = 5;
    static final float TOO_HURT = 6f;
    static final int PORTAL_SIGHT = 48;

    private final boolean anyDimension;
    private boolean started;
    private int failuresInRow;

    public FightDragonRoutine() {
        this(false);
    }

    /** {@code anyDimension} lets game tests run it in the overworld. */
    public FightDragonRoutine(boolean anyDimension) {
        this.anyDimension = anyDimension;
    }

    @Override
    protected Action nextStage(BotContext ctx) {
        failuresInRow = 0;
        if (!started) {
            started = true;
            String problem = TheEnd.problem(ctx, "fight_dragon", anyDimension);
            if (problem != null) return new Fail("fight dragon", problem);
        }
        return decide(ctx);
    }

    @Override
    protected Action retry(BotContext ctx, Action failed, String reason) {
        if (failed instanceof Fail || ++failuresInRow >= MAX_FAILURES) return null;
        Action next = decide(ctx);
        return next != null ? next : new Instant();
    }

    private Action decide(BotContext ctx) {
        AiPlayer p = ctx.player();
        Move move = DragonTactics.next(situation(p));
        if (move == Move.DONE) return null;
        if (p.getHealth() <= TOO_HURT) return new Fail("fight dragon", "too hurt to keep fighting (health " + Math.round(p.getHealth()) + ")");
        return switch (move) {
            case SHOOT_CRYSTAL -> new ShootCrystalsAction(10, anyDimension);
            case BREAK_CAGE -> new BreakCageAction(anyDimension);
            default -> new HitDragonAction(10, anyDimension);
        };
    }

    static Situation situation(AiPlayer p) {
        List<EndCrystal> crystals = DragonView.crystals(p);
        int open = 0, caged = 0;
        for (EndCrystal c : crystals) {
            if (DragonView.caged(p.level(), c)) caged++;
            else if (DragonView.visible(p, c)) open++;
        }
        EnderDragon dragon = DragonView.dragon(p).orElse(null);
        boolean dead = dragon != null ? DragonView.dead(dragon) : exitPortalVisible(p);
        boolean perched = dragon != null && !dead && DragonView.perched(dragon);
        double head = dragon == null ? Double.MAX_VALUE : p.distanceTo(dragon.head);
        return new Situation(inBreath(p), open, caged, perched, head, dead, InventoryOps.has(p, Items.BOW, 1),
                InventoryOps.count(p, s -> s.is(ItemTags.ARROWS)), InventoryOps.throwawayCount(p), p.getHealth());
    }

    static boolean inBreath(AiPlayer p) {
        for (AreaEffectCloud c : DragonView.breath(p)) {
            if (Math.hypot(p.getX() - c.getX(), p.getZ() - c.getZ()) <= c.getRadius() + Dodge.MARGIN && Math.abs(p.getY() - c.getY()) <= 4) {
                return true;
            }
        }
        return false;
    }

    static boolean exitPortalVisible(AiPlayer p) {
        return !Finder.visibleStates(p, s -> s.is(Blocks.END_PORTAL), PORTAL_SIGHT).isEmpty();
    }

    @Override
    protected String name() {
        return "fight the dragon";
    }

    @Override
    protected String summary() {
        return "the dragon is dead; the exit portal is open";
    }

    @Override
    public int timeoutTicks() {
        return 36000;
    }

    /** A stage that is done at once, so the routine can look at the situation again. */
    private static final class Instant implements Action {
        @Override
        public ActionStatus tick(BotContext ctx) {
            return ActionStatus.DONE;
        }

        @Override
        public String describe() {
            return "look again";
        }

        @Override
        public int timeoutTicks() {
            return 1;
        }
    }
}
