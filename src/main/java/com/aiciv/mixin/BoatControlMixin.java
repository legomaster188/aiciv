package com.aiciv.mixin;

import com.aiciv.bot.AiPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoat.class)
abstract class BoatControlMixin {
    @Shadow
    private void controlBoat() {
        throw new AssertionError();
    }

    /**
     * A client turns its paddle inputs ({@code setInput}) into motion before it moves its boat; only the client does
     * that, so for a boat a bot steers (which the server drives) the server does the same step.
     */
    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;floatBoat()V", shift = At.Shift.AFTER))
    private void aiciv$paddleForBots(CallbackInfo ci) {
        AbstractBoat self = (AbstractBoat) (Object) this;
        if (!self.level().isClientSide() && self.getControllingPassenger() instanceof AiPlayer) controlBoat();
    }
}
