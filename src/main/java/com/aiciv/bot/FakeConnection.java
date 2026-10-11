package com.aiciv.bot;

import io.netty.channel.ChannelFutureListener;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** A connection with no socket behind it. Every outbound packet is dropped. */
public final class FakeConnection extends Connection {
    private static final SocketAddress ADDRESS = InetSocketAddress.createUnresolved("aiciv", 0);

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        ((com.aiciv.mixin.ConnectionAccessor) (Object) this).aiciv$setChannel(new io.netty.channel.embedded.EmbeddedChannel());
    }

    @Override public void send(Packet<?> packet) {}
    @Override public void send(Packet<?> packet, ChannelFutureListener listener) {}
    @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
    @Override public boolean isConnected() { return true; }
    @Override public SocketAddress getRemoteAddress() { return ADDRESS; }
    @Override public String getLoggableAddress(boolean logIps) { return "aiciv"; }
    @Override public void setReadOnly() {}
    @Override public void handleDisconnection() {}
    @Override public void disconnect(Component reason) {}
    @Override public void disconnect(DisconnectionDetails details) {}
    @Override public void setListenerForServerboundHandshake(PacketListener listener) {}
    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {}
    @Override public void setupOutboundProtocol(ProtocolInfo<?> info) {}
    @Override public void tick() {}
}
