package net.minecraft.network.protocol.handshake;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;

public record ClientIntentionPacket(ClientIntent intention, PacketType<? extends Packet<Object>> type) implements Packet<Object> {
    @Override
    public boolean isTerminal() {
        return true;
    }
}
