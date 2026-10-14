package com.aiciv.animals;

import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Riding;
import com.aiciv.world.Sight;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Takes the lead off the nearest leashed animal of one kind and picks the lead up. An animal tied to a fence comes
 * back onto the bot's own lead first (the click on the knot does that), then the click on the animal drops the lead.
 */
public final class UnleashAction extends InteractAction {
    static final double COLLECT_RADIUS = 8;
    static final int COLLECT_TICKS = 100;

    private DropCollector collector;

    public UnleashAction(String animal) {
        super(animal);
    }

    @Override
    protected String find(AiPlayer p) {
        EntityType<?> type = Animals.type(label);
        if (type == null) return "no animal called " + label;
        target = Animals.nearest(p, type, Mob.class, Animals.RADIUS,
                m -> m.isLeashed() && (m.getLeashHolder() == p || m.getLeashHolder() instanceof LeashFenceKnotEntity)).orElse(null);
        return target == null ? "no leashed " + Animals.id(type) + " within 16 blocks" : null;
    }

    @Override
    protected Entity clickTarget(AiPlayer p) {
        Entity holder = ((Mob) target).getLeashHolder();
        return holder instanceof LeashFenceKnotEntity ? holder : target;
    }

    /**
     * A knot sits inside the fence post, so no ray reaches its centre without crossing the post. It is seen through
     * its fence instead: the bot walks to where it can see the fence block and clicks the knot from there.
     */
    @Override
    protected Approach approachTo(Entity click) {
        return click instanceof LeashFenceKnotEntity knot ? Approach.block(knot.getPos(), REACH) : super.approachTo(click);
    }

    @Override
    protected BlockUse.Result click(AiPlayer p, Entity click) {
        if (!(click instanceof LeashFenceKnotEntity knot)) return super.click(p, click);
        if (!p.isWithinEntityInteractionRange(knot, 0.0)) return BlockUse.Result.OUT_OF_REACH;
        if (!Sight.canSee(p, knot.getPos())) return BlockUse.Result.NOT_VISIBLE;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, knot.getBoundingBox().getCenter());
        boolean used = p.interactOn(knot, InteractionHand.MAIN_HAND, Vec3.ZERO).consumesAction();
        return used ? BlockUse.Result.USED : BlockUse.Result.NO_EFFECT;
    }

    @Override
    protected boolean hold(AiPlayer p) {
        return Riding.selectEmptyHand(p);
    }

    @Override
    protected String missing() {
        return "no free hand for the click";
    }

    @Override
    protected boolean done(AiPlayer p) {
        return !((Mob) target).isLeashed();
    }

    @Override
    protected String success(AiPlayer p) {
        return "took the lead off the " + Animals.id(target.getType());
    }

    @Override
    protected void onDone(BotContext ctx) {
        collector = new DropCollector(s -> s.is(Items.LEAD), COLLECT_RADIUS, COLLECT_TICKS);
    }

    @Override
    protected boolean afterwards(BotContext ctx) {
        return collector.tick(ctx);
    }

    @Override
    protected void stopAfterwards(BotContext ctx) {
        if (collector != null) collector.stop(ctx);
    }

    @Override
    protected String stuck() {
        return "the " + label + " is still on its lead";
    }

    @Override
    protected String verb() {
        return "unleash";
    }
}
