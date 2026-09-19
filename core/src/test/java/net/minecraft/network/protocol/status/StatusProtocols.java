package net.minecraft.network.protocol.status;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;
import net.momirealms.sparrow.ui.network.packet.PacketFlow;

public final class StatusProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(PacketFlow.SERVERBOUND,
            "minecraft:status_request",
            "minecraft:ping_request"
    );
    public static final ProtocolInfo.DetailsProvider CLIENTBOUND_TEMPLATE = FakeProtocolTemplate.of(PacketFlow.CLIENTBOUND,
            "minecraft:status_response",
            "minecraft:pong_response"
    );
    private StatusProtocols() {
    }
}
