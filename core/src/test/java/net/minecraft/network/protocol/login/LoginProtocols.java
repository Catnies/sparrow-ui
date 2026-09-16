package net.minecraft.network.protocol.login;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;

public final class LoginProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(
            "minecraft:hello",
            "minecraft:key",
            "minecraft:custom_query_answer",
            "minecraft:login_acknowledged"
    );
    public static final ProtocolInfo.DetailsProvider CLIENTBOUND_TEMPLATE = FakeProtocolTemplate.of(
            "minecraft:login_disconnect",
            "minecraft:hello",
            "minecraft:login_finished"
    );
    private LoginProtocols() {
    }
}
