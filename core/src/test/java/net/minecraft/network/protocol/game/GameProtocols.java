package net.minecraft.network.protocol.game;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;
import net.momirealms.sparrow.ui.network.PacketFlow;

public final class GameProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(PacketFlow.SERVERBOUND,
            "minecraft:accept_teleportation",
            "minecraft:bundle_item_selected",
            "minecraft:container_button_click",
            "minecraft:container_click",
            "minecraft:container_close",
            "minecraft:container_slot_state_changed",
            "minecraft:configuration_acknowledged",
            "minecraft:place_recipe",
            "minecraft:pong",
            "minecraft:rename_item",
            "minecraft:select_trade",
            "minecraft:custom_payload"
    );
    public static final ProtocolInfo.DetailsProvider CLIENTBOUND_TEMPLATE = FakeProtocolTemplate.of(PacketFlow.CLIENTBOUND,
            "minecraft:bundle_delimiter",
            "minecraft:login",
            "minecraft:merchant_offers",
            "minecraft:start_configuration",
            "minecraft:update_recipes",
            "minecraft:custom_payload"
    );
    private GameProtocols() {
    }
}
