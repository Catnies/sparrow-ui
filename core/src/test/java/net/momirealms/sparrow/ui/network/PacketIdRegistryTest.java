package net.momirealms.sparrow.ui.network;

import net.momirealms.sparrow.ui.network.packet.*;
import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PacketIdRegistryTest {

    private static PacketIdRegistry registry;

    @BeforeAll
    static void setUp() {
        BukkitProxy.init("1.21.8", List.of("paper"));
        registry = new PacketIdRegistry();
    }

    @Test
    void numbersPacketsByDeclarationOrder() {
        assertEquals(0, registry.byName("minecraft:accept_teleportation", ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertEquals(3, registry.byName("minecraft:container_click", ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertEquals(10, registry.byName("minecraft:select_trade", ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertEquals(1, registry.byName("minecraft:login", ConnectionState.PLAY, PacketFlow.CLIENTBOUND));
    }

    @Test
    void reportsMissingPacketAsMinusOne() {
        assertEquals(-1, registry.byName("minecraft:not_a_packet", ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertEquals(-1, registry.byName("minecraft:container_click", ConnectionState.PLAY, PacketFlow.CLIENTBOUND), "方向不对时不该命中");
        assertEquals(-1, registry.byName("minecraft:container_click", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND), "阶段不对时不该命中");
    }

    @Test
    void keepsSameNameApartAcrossFlows() {
        assertEquals(0, registry.byName("minecraft:hello", ConnectionState.LOGIN, PacketFlow.SERVERBOUND));
        assertEquals(1, registry.byName("minecraft:hello", ConnectionState.LOGIN, PacketFlow.CLIENTBOUND));
        assertEquals(1, registry.byName("minecraft:finish_configuration", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND));
        assertEquals(0, registry.byName("minecraft:finish_configuration", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND));
    }

    @Test
    void sizesEachRouteToCoverItsLargestId() {
        assertEquals(12, registry.count(ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertEquals(6, registry.count(ConnectionState.PLAY, PacketFlow.CLIENTBOUND));
        assertEquals(1, registry.count(ConnectionState.HANDSHAKING, PacketFlow.SERVERBOUND));
        assertEquals(4, registry.count(ConnectionState.LOGIN, PacketFlow.SERVERBOUND));
        assertTrue(registry.byName("minecraft:select_trade", ConnectionState.PLAY, PacketFlow.SERVERBOUND)
                < registry.count(ConnectionState.PLAY, PacketFlow.SERVERBOUND));
    }

    @Test
    void leavesHandshakeClientboundEmpty() {
        assertEquals(0, registry.count(ConnectionState.HANDSHAKING, PacketFlow.CLIENTBOUND));
        assertEquals(-1, registry.byName("minecraft:intention", ConnectionState.HANDSHAKING, PacketFlow.CLIENTBOUND));
    }

    @Test
    void resolvesEquivalentDescriptorsToTheSameRuntimeTypeAndId() {
        PacketType descriptor = new PacketType("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        assertEquals(9, registry.id(PacketTypes.Play.Serverbound.RENAME_ITEM));
        assertEquals(9, registry.id(descriptor));
        assertSame(registry.nativeType(PacketTypes.Play.Serverbound.RENAME_ITEM), registry.nativeType(descriptor));
        assertEquals(1 << ConnectionState.PLAY.ordinal(), registry.stateMask(registry.nativeType(descriptor)));
    }

    @Test
    void resolvesTheWireBundleDelimiterRatherThanTheObjectContainer() {
        assertEquals(0, registry.id(PacketTypes.Play.Clientbound.BUNDLE_DELIMITER));
        assertEquals(-1, registry.byName("minecraft:bundle", ConnectionState.PLAY, PacketFlow.CLIENTBOUND));
    }

    @Test
    void leavesMissingTypesUnbound() {
        PacketType missing = new PacketType("minecraft:unknown", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        assertEquals(-1, registry.id(missing));
        assertNull(registry.nativeType(missing));
        assertEquals(-1, registry.id(new PacketType("minecraft:rename_item", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND)));
        assertEquals(-1, registry.id(new PacketType("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.CLIENTBOUND)));
        assertNull(registry.nativeType(new PacketType("minecraft:rename_item", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND)));
        assertNull(registry.nativeType(new PacketType("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.CLIENTBOUND)));
    }

    @Test
    void sharesNativeTypesAcrossStagesButKeepsEachNetworkId() {
        Object play = registry.nativeType(PacketTypes.Play.Serverbound.CUSTOM_PAYLOAD);
        Object configuration = registry.nativeType(PacketTypes.Configuration.Serverbound.CUSTOM_PAYLOAD);
        assertEquals(11, registry.id(PacketTypes.Play.Serverbound.CUSTOM_PAYLOAD));
        assertEquals(3, registry.id(PacketTypes.Configuration.Serverbound.CUSTOM_PAYLOAD));
        assertSame(play, configuration);
        assertEquals((1 << ConnectionState.PLAY.ordinal()) | (1 << ConnectionState.CONFIGURATION.ordinal()), registry.stateMask(play));
    }

    @Test
    void distinguishesNativeTypeIdentityFromValueEquality() {
        Object registered = registry.nativeType(PacketTypes.Play.Serverbound.CUSTOM_PAYLOAD);
        Object equalButDifferent = new net.minecraft.network.protocol.PacketType<>("minecraft:custom_payload");
        assertEquals(registered, equalButDifferent);
        assertEquals(0, registry.stateMask(equalButDifferent));
    }

    @Test
    void keepsOppositeDirectionNativeTypesSeparate() {
        Object serverbound = registry.nativeType(PacketTypes.Login.Serverbound.HELLO);
        Object clientbound = registry.nativeType(PacketTypes.Login.Clientbound.HELLO);
        assertNotSame(serverbound, clientbound);
    }

    @Test
    void resolvesTheSameDescriptorInSeparateRegistries() {
        PacketIdRegistry other = new PacketIdRegistry();
        assertEquals(registry.id(PacketTypes.Play.Serverbound.RENAME_ITEM), other.id(PacketTypes.Play.Serverbound.RENAME_ITEM));
        assertSame(registry.nativeType(PacketTypes.Play.Serverbound.RENAME_ITEM), other.nativeType(PacketTypes.Play.Serverbound.RENAME_ITEM));
    }

    @Test
    void readsNativeTypeThroughTheCommonPacketProxy() {
        net.minecraft.network.protocol.PacketType<?> nativeType = (net.minecraft.network.protocol.PacketType<?>) registry.nativeType(PacketTypes.Play.Serverbound.RENAME_ITEM);
        NativePacket packet = new NativePacket(nativeType);
        assertSame(nativeType, PacketProxy.INSTANCE.type(packet));
    }

    public record NativePacket(net.minecraft.network.protocol.PacketType<?> nativeType) implements net.minecraft.network.protocol.Packet<Object> {
        @Override
        @SuppressWarnings("unchecked")
        public net.minecraft.network.protocol.PacketType<? extends net.minecraft.network.protocol.Packet<Object>> type() {
            return (net.minecraft.network.protocol.PacketType<? extends net.minecraft.network.protocol.Packet<Object>>) this.nativeType;
        }
    }
}
