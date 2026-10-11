package com.aiciv.bot;

import com.mojang.authlib.GameProfile;
import java.util.Set;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.phys.Vec3;

/** A server-side player with no client. It runs its own physics every tick. */
public final class AiPlayer extends ServerPlayer {
    private AiPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    /**
     * Logs a new AI player in through the normal player list, so it gets a tab-list entry,
     * a join message and vanilla playerdata saving. Call on the server thread.
     *
     * @param onDisconnect runs when the server asks this player to disconnect
     */
    public static AiPlayer join(MinecraftServer server, String name, Runnable onDisconnect) {
        GameProfile profile = UUIDUtil.createOfflineProfile(name);
        AiPlayer player = new AiPlayer(server, server.overworld(), profile);
        FakeConnection connection = new FakeConnection();
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, player.clientInformation(), false);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        // The listener's constructor assigns player.connection, replacing the vanilla listener.
        new FakeGamePacketListener(server, connection, player, cookie, onDisconnect);
        return player;
    }

    public void moveTo(ServerLevel level, Vec3 pos, float yaw) {
        teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, 0.0F, true);
    }

    public void stopMoving() {
        xxa = 0.0F;
        zza = 0.0F;
        setJumping(false);
    }

    @Override
    public void tick() {
        if (level().getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            level().getChunkSource().move(this);
        }
        super.tick();
        // A real player's physics run from its network listener's tick. Ours never ticks.
        doTick();
    }
}
