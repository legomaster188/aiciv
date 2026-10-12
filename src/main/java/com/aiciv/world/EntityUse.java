package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

/** Right-clicks an entity (villager, animal, name tag target) within a survival player's reach and sight. */
public final class EntityUse {
    private EntityUse() {}

    public static BlockUse.Result interact(AiPlayer p, Entity target, InteractionHand hand) {
        if (!p.isWithinEntityInteractionRange(target, 0.0)) return BlockUse.Result.OUT_OF_REACH;
        if (!p.hasLineOfSight(target)) return BlockUse.Result.NOT_VISIBLE;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        // The client sends where on the entity it clicked, relative to the entity's position.
        boolean used = p.interactOn(target, hand, target.getBoundingBox().getCenter().subtract(target.position())).consumesAction();
        return used ? BlockUse.Result.USED : BlockUse.Result.NO_EFFECT;
    }
}
