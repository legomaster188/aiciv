package com.aiciv.mixin;

import com.aiciv.bot.AiPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class VehicleControlMixin {
    /** A vehicle trusts its driver's client; a bot has none, so the server drives a vehicle whose driver is a bot. */
    @Inject(method = "isClientAuthoritative", at = @At("HEAD"), cancellable = true)
    private void aiciv$serverDrivesBotVehicles(CallbackInfoReturnable<Boolean> cir) {
        if (((Entity) (Object) this).getControllingPassenger() instanceof AiPlayer) cir.setReturnValue(false);
    }
}
