package net.momirealms.sparrow.ui.network;

import net.momirealms.sparrow.ui.proxy.minecraft.network.ProtocolInfoDetailsProviderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.ProtocolInfoDetailsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketTypeProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.configuration.ConfigurationProtocolsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.GameProtocolsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.handshake.HandshakeProtocolsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.login.LoginProtocolsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.status.StatusProtocolsProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@ApiStatus.Experimental
public final class PacketIdRegistry {
    private final Map<String, Integer>[][] packetIds;
    private final int[][] packetCounts;

    /**
     * 枚举当前服务端的五个协议阶段并建立只读索引.
     */
    @SuppressWarnings("unchecked")
    public PacketIdRegistry() {
        this.packetIds = (Map<String, Integer>[][]) new Map[ConnectionState.values().length][PacketFlow.values().length];
        this.packetCounts = new int[ConnectionState.values().length][PacketFlow.values().length];
        for (ConnectionState state : ConnectionState.values()) {
            for (PacketFlow flow : PacketFlow.values()) {
                this.packetIds[state.ordinal()][flow.ordinal()] = Map.of();
            }
        }
        for (ProtocolTemplate template : protocolTemplates()) {
            PacketTable table = readPacketTable(template.template());
            this.packetIds[template.state().ordinal()][template.flow().ordinal()] = table.ids();
            this.packetCounts[template.state().ordinal()][template.flow().ordinal()] = table.count();
        }
    }

    /**
     * 按注册名查找当前服务端使用的包 ID.
     *
     * @param name 完整注册名, 例如 {@code minecraft:container_click}
     * @param state 包所属协议阶段
     * @param flow 包的传输方向
     * @return 当前 ID, 不存在时为 -1
     */
    public int byName(@NotNull String name, @NotNull ConnectionState state, @NotNull PacketFlow flow) {
        return this.packetIds[state.ordinal()][flow.ordinal()].getOrDefault(name, -1);
    }

    /**
     * 返回一个协议阶段和方向实际注册的 ID 空间长度.
     *
     * @param state 包所属协议阶段
     * @param flow 包的传输方向
     * @return 可用于定长路由数组的长度
     */
    public int count(@NotNull ConnectionState state, @NotNull PacketFlow flow) {
        return this.packetCounts[state.ordinal()][flow.ordinal()];
    }

    void dump(@NotNull Consumer<String> output) {
        for (ConnectionState state : ConnectionState.values()) {
            for (PacketFlow flow : PacketFlow.values()) {
                ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(this.packetIds[state.ordinal()][flow.ordinal()].entrySet());
                entries.sort(Comparator.comparingInt(Map.Entry::getValue));
                for (int index = 0; index < entries.size(); index++) {
                    Map.Entry<String, Integer> entry = entries.get(index);
                    output.accept(state + "/" + flow + " " + entry.getValue() + " " + entry.getKey());
                }
            }
        }
    }

    private static List<ProtocolTemplate> protocolTemplates() {
        return List.of(
                new ProtocolTemplate(ConnectionState.HANDSHAKING, PacketFlow.SERVERBOUND, HandshakeProtocolsProxy.INSTANCE.serverboundTemplate()),
                new ProtocolTemplate(ConnectionState.STATUS, PacketFlow.SERVERBOUND, StatusProtocolsProxy.INSTANCE.serverboundTemplate()),
                new ProtocolTemplate(ConnectionState.STATUS, PacketFlow.CLIENTBOUND, StatusProtocolsProxy.INSTANCE.clientboundTemplate()),
                new ProtocolTemplate(ConnectionState.LOGIN, PacketFlow.SERVERBOUND, LoginProtocolsProxy.INSTANCE.serverboundTemplate()),
                new ProtocolTemplate(ConnectionState.LOGIN, PacketFlow.CLIENTBOUND, LoginProtocolsProxy.INSTANCE.clientboundTemplate()),
                new ProtocolTemplate(ConnectionState.PLAY, PacketFlow.SERVERBOUND, GameProtocolsProxy.INSTANCE.serverboundTemplate()),
                new ProtocolTemplate(ConnectionState.PLAY, PacketFlow.CLIENTBOUND, GameProtocolsProxy.INSTANCE.clientboundTemplate()),
                new ProtocolTemplate(ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND, ConfigurationProtocolsProxy.INSTANCE.serverboundTemplate()),
                new ProtocolTemplate(ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND, ConfigurationProtocolsProxy.INSTANCE.clientboundTemplate())
        );
    }

    private static PacketTable readPacketTable(Object template) {
        HashMap<String, Integer> ids = new HashMap<>();
        int[] largestId = {-1};
        Class<?> visitorClass = ProtocolInfoDetailsProxy.PACKET_VISITOR_CLASS;
        if (visitorClass == null) {
            throw new IllegalStateException("Missing NMS packet visitor");
        }
        Object visitor = Proxy.newProxyInstance(visitorClass.getClassLoader(), new Class<?>[]{visitorClass}, (proxy, method, arguments) -> {
            if (method.getName().equals("accept")) {
                Object packetType = arguments[0];
                int packetId = (int) arguments[1];
                String name = PacketTypeProxy.INSTANCE.id(packetType).toString();
                ids.put(name, packetId);
                largestId[0] = Math.max(largestId[0], packetId);
                return null;
            }
            return switch (method.getName()) {
                case "toString" -> "SparrowUI packet table visitor";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new UnsupportedOperationException(method.toString());
            };
        });
        Object details = ProtocolInfoDetailsProviderProxy.INSTANCE.details(template);
        ProtocolInfoDetailsProxy.INSTANCE.listPackets(details, visitor);
        return new PacketTable(Map.copyOf(ids), largestId[0] + 1);
    }

    private record ProtocolTemplate(ConnectionState state, PacketFlow flow, Object template) {
    }

    private record PacketTable(Map<String, Integer> ids, int count) {
    }
}
