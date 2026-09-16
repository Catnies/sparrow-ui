package net.minecraft.network.protocol.game;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.FakeProtocolTemplate;

public final class GameProtocols {

    public static final ProtocolInfo.DetailsProvider SERVERBOUND_TEMPLATE = FakeProtocolTemplate.of(
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
            "minecraft:select_trade"
    );
    public static final ProtocolInfo.DetailsProvider CLIENTBOUND_TEMPLATE = FakeProtocolTemplate.of(
            "minecraft:bundle",
            "minecraft:login",
            "minecraft:merchant_offers",
            "minecraft:start_configuration",
            "minecraft:update_recipes"
    );
    private GameProtocols() {
    }
}
