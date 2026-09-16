package net.minecraft.network.protocol.status;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;

public final class StatusProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(
            "minecraft:status_request",
            "minecraft:ping_request"
    );
    public static final ProtocolInfo.DetailsProvider CLIENTBOUND_TEMPLATE = FakeProtocolTemplate.of(
            "minecraft:status_response",
            "minecraft:pong_response"
    );
    private StatusProtocols() {
    }
}
