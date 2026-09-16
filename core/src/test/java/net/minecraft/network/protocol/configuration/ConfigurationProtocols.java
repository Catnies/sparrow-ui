package net.minecraft.network.protocol.configuration;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;

public final class ConfigurationProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(
            "minecraft:client_information",
            "minecraft:finish_configuration",
            "minecraft:keep_alive"
    );
    public static final ProtocolInfo.DetailsProvider CLIENTBOUND_TEMPLATE = FakeProtocolTemplate.of(
            "minecraft:finish_configuration",
            "minecraft:keep_alive",
            "minecraft:registry_data",
            "minecraft:update_tags"
    );
    private ConfigurationProtocols() {
    }
}
