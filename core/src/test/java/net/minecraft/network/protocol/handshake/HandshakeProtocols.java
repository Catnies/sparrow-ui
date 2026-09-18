package net.minecraft.network.protocol.handshake;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;
import net.momirealms.sparrow.ui.network.PacketFlow;

public final class HandshakeProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(PacketFlow.SERVERBOUND,
            "minecraft:intention"
    );
    private HandshakeProtocols() {
    }
}
