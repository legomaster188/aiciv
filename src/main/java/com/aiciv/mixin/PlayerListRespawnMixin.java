package com.aiciv.mixin;

import com.aiciv.bot.AiPlayer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A dead AI player respawns as an AI player, not as a plain ServerPlayer. */
@Mixin(PlayerList.class)
abstract class PlayerListRespawnMixin {
    @WrapOperation(method = "respawn",
            at = @At(value = "NEW", target = "net/minecraft/server/level/ServerPlayer"))
    private ServerPlayer aiciv$respawnAsAi(MinecraftServer server, ServerLevel level, GameProfile profile,
                                           ClientInformation info, Operation<ServerPlayer> original,
                                           @Local(argsOnly = true) ServerPlayer old) {
        if (old instanceof AiPlayer) return AiPlayer.create(server, level, profile);
        return original.call(server, level, profile, info);
    }
}
