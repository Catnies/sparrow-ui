package net.minecraft.network.protocol.configuration;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;
import net.momirealms.sparrow.ui.network.PacketFlow;

public final class ConfigurationProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(PacketFlow.SERVERBOUND,
            "minecraft:client_information",
            "minecraft:finish_configuration",
            "minecraft:keep_alive",
            "minecraft:custom_payload"
    );
    public static final ProtocolInfo.DetailsProvider CLIENTBOUND_TEMPLATE = FakeProtocolTemplate.of(PacketFlow.CLIENTBOUND,
            "minecraft:finish_configuration",
            "minecraft:keep_alive",
            "minecraft:registry_data",
            "minecraft:update_tags",
            "minecraft:custom_payload"
    );
    private ConfigurationProtocols() {
    }
}
