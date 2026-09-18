package net.minecraft.network.protocol.game;

import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;

public final class ClientboundBundlePacket extends BundlePacket {
    public ClientboundBundlePacket(Iterable<?> packets) {
        super(packets);
    }

    @Override
    public PacketType<? extends Packet<Object>> type() {
        throw new AssertionError("bundle container must be expanded before type lookup");
    }
}
