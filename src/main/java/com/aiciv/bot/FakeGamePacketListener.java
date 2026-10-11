package com.aiciv.bot;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Game listener for an {@link AiPlayer}. The server never ticks it (the fake connection is not
 * in the server's connection list), so there is no keep-alive timeout. Disconnect requests
 * (kick, duplicate login, shutdown) go to a callback instead of a socket.
 */
public final class FakeGamePacketListener extends ServerGamePacketListenerImpl {
    private final Runnable onDisconnect;

    public FakeGamePacketListener(MinecraftServer server, FakeConnection connection, AiPlayer player,
                                  CommonListenerCookie cookie, Runnable onDisconnect) {
        super(server, connection, player, cookie);
        this.onDisconnect = onDisconnect;
    }

    @Override public void send(Packet<?> packet) {}
    @Override public void send(Packet<?> packet, ChannelFutureListener listener) {}

    @Override
    public void disconnect(DisconnectionDetails details) {
        onDisconnect.run();
    }
}
