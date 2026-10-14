package com.aiciv.dragon;

import com.aiciv.bot.AiPlayer;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** What a client shows of the fight: entity positions and synced data (the dragon's phase and health, crystals, breath clouds). */
public final class DragonView {
    static final double DRAGON_RANGE = 192;
    static final double CRYSTAL_RANGE = 160;
    static final double BREATH_RANGE = 16;

    private DragonView() {}

    /** The nearest dragon within 192 blocks. */
    public static Optional<EnderDragon> dragon(AiPlayer p) {
        return p.level().getEntitiesOfClass(EnderDragon.class, p.getBoundingBox().inflate(DRAGON_RANGE),
                        d -> !d.isRemoved() && d.distanceTo(p) <= DRAGON_RANGE)
                .stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p)));
    }

    /** Sitting on the fountain: scanning, flaming or attacking, from the synced phase. */
    public static boolean perched(EnderDragon d) {
        return d.getPhaseManager().getCurrentPhase().isSitting();
    }

    public static boolean dead(EnderDragon d) {
        return d.isRemoved() || d.dragonDeathTime > 0 || d.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING;
    }

    /** Living end crystals within 160 blocks, nearest first. */
    public static List<EndCrystal> crystals(AiPlayer p) {
        return p.level().getEntitiesOfClass(EndCrystal.class, p.getBoundingBox().inflate(CRYSTAL_RANGE),
                        c -> !c.isRemoved() && c.distanceTo(p) <= CRYSTAL_RANGE)
                .stream().sorted(Comparator.comparingDouble(c -> c.distanceToSqr(p))).toList();
    }

    /** Iron bars within 2 blocks sideways and 0 to 2 above the crystal's block: the cage of a guarded tower. */
    public static boolean caged(Level level, EndCrystal c) {
        BlockPos at = c.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(at.offset(-2, 0, -2), at.offset(2, 2, 2))) {
            if (level.getBlockState(b).is(Blocks.IRON_BARS)) return true;
        }
        return false;
    }

    public static boolean visible(AiPlayer p, Entity e) {
        return p.hasLineOfSight(e);
    }

    /** Dragon breath clouds within 16 blocks. */
    public static List<AreaEffectCloud> breath(AiPlayer p) {
        return p.level().getEntitiesOfClass(AreaEffectCloud.class, p.getBoundingBox().inflate(BREATH_RANGE),
                c -> !c.isRemoved() && c.getParticle().getType() == ParticleTypes.DRAGON_BREATH);
    }
}
