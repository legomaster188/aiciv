package com.aiciv.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The bobber dips for a real client through the synced DATA_BITING flag, so reading the bite window is fair. */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
    @Accessor("nibble")
    int aiciv$nibble();
}
