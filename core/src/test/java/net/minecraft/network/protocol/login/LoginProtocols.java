package net.minecraft.network.protocol.login;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;
import net.momirealms.sparrow.ui.network.packet.PacketFlow;

public final class LoginProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(PacketFlow.SERVERBOUND,
            "minecraft:hello",
            "minecraft:key",
            "minecraft:custom_query_answer",
            "minecraft:login_acknowledged"
    );
    public static final ProtocolInfo.DetailsProvider CLIENTBOUND_TEMPLATE = FakeProtocolTemplate.of(PacketFlow.CLIENTBOUND,
            "minecraft:login_disconnect",
            "minecraft:hello",
            "minecraft:login_finished"
    );
    private LoginProtocols() {
    }
}
