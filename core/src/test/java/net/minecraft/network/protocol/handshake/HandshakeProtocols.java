package net.minecraft.network.protocol.handshake;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;

public final class HandshakeProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(
            "minecraft:intention"
    );
    private HandshakeProtocols() {
    }
}
