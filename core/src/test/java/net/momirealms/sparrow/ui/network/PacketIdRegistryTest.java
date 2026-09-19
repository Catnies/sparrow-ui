package net.momirealms.sparrow.ui.network;

import net.momirealms.sparrow.ui.network.packet.*;
import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        PacketType discovered = registry.find(descriptor.name(), descriptor.state(), descriptor.flow());
        assertEquals(descriptor, discovered);
        assertEquals(9, registry.id(discovered));
        assertEquals(9, registry.id(descriptor));
        assertSame(registry.nativeType(discovered), registry.nativeType(descriptor));
    }

    @Test
    void listsRuntimeTypesInIdOrderAndReusesThemForLookups() {
        for (ConnectionState state : ConnectionState.values()) {
            for (PacketFlow flow : PacketFlow.values()) {
                List<PacketType> types = registry.types(state, flow);
                assertEquals(registry.count(state, flow), types.size());
                int previousId = -1;
                for (int index = 0; index < types.size(); index++) {
                    PacketType type = types.get(index);
                    int id = registry.id(type);
                    assertEquals(state, type.state());
                    assertEquals(flow, type.flow());
                    assertTrue(id > previousId);
                    assertSame(type, registry.find(type.name(), state, flow));
                    assertSame(type, registry.type(id, state, flow));
                    previousId = id;
                }
                assertSame(types, registry.types(state, flow));
                assertThrows(UnsupportedOperationException.class, () -> types.add(new PacketType("test:packet", state, flow)));
            }
        }
    }

    @Test
    void reportsAbsentNamesAndIdsWithoutInventingTypes() {
        assertNull(registry.find("minecraft:unknown", ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertNull(registry.find("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.CLIENTBOUND));
        assertNull(registry.find("minecraft:rename_item", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND));
        assertNull(registry.type(-1, ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertNull(registry.type(registry.count(ConnectionState.PLAY, PacketFlow.SERVERBOUND), ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertNull(registry.type(Integer.MAX_VALUE, ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertNull(registry.type(0, ConnectionState.HANDSHAKING, PacketFlow.CLIENTBOUND));
        assertTrue(registry.types(ConnectionState.HANDSHAKING, PacketFlow.CLIENTBOUND).isEmpty());
    }

    @Test
    void resolvesTheWireBundleDelimiterRatherThanTheObjectContainer() {
        assertEquals(0, registry.id(new PacketType("minecraft:bundle_delimiter", ConnectionState.PLAY, PacketFlow.CLIENTBOUND)));
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
        Object play = registry.nativeType(new PacketType("minecraft:custom_payload", ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        Object configuration = registry.nativeType(new PacketType("minecraft:custom_payload", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND));
        assertEquals(11, registry.id(new PacketType("minecraft:custom_payload", ConnectionState.PLAY, PacketFlow.SERVERBOUND)));
        assertEquals(3, registry.id(new PacketType("minecraft:custom_payload", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND)));
        assertSame(play, configuration);
    }

    @Test
    void keepsOppositeDirectionNativeTypesSeparate() {
        Object serverbound = registry.nativeType(new PacketType("minecraft:hello", ConnectionState.LOGIN, PacketFlow.SERVERBOUND));
        Object clientbound = registry.nativeType(new PacketType("minecraft:hello", ConnectionState.LOGIN, PacketFlow.CLIENTBOUND));
        assertNotSame(serverbound, clientbound);
    }

    @Test
    void resolvesTheSameDescriptorInSeparateRegistries() {
        PacketIdRegistry other = new PacketIdRegistry();
        assertEquals(registry.id(new PacketType("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND)), other.id(new PacketType("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND)));
        assertSame(registry.nativeType(new PacketType("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND)), other.nativeType(new PacketType("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND)));
    }

    @Test
    void readsNativeTypeThroughTheCommonPacketProxy() {
        net.minecraft.network.protocol.PacketType<?> nativeType = (net.minecraft.network.protocol.PacketType<?>) registry.nativeType(new PacketType("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND));
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
