package net.momirealms.sparrow.ui.network;

import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(11, registry.count(ConnectionState.PLAY, PacketFlow.SERVERBOUND));
        assertEquals(5, registry.count(ConnectionState.PLAY, PacketFlow.CLIENTBOUND));
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
    void dumpsEveryEntrySortedById() {
        ArrayList<String> lines = new ArrayList<>();
        registry.dump(lines::add);

        assertTrue(lines.contains("PLAY/SERVERBOUND 3 minecraft:container_click"));
        assertTrue(lines.contains("HANDSHAKING/SERVERBOUND 0 minecraft:intention"));
        List<String> playServerbound = lines.stream().filter(line -> line.startsWith("PLAY/SERVERBOUND ")).toList();

        assertEquals(11, playServerbound.size());
        assertEquals("PLAY/SERVERBOUND 0 minecraft:accept_teleportation", playServerbound.getFirst());
        assertEquals("PLAY/SERVERBOUND 10 minecraft:select_trade", playServerbound.getLast());
    }
}
