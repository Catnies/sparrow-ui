package net.momirealms.sparrow.ui.network;

import net.momirealms.sparrow.ui.proxy.minecraft.network.ProtocolInfoDetailsProviderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.ProtocolInfoDetailsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.ProtocolInfoUnboundProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketTypeProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.configuration.ConfigurationProtocolsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.GameProtocolsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.handshake.HandshakeProtocolsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.login.LoginProtocolsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.status.StatusProtocolsProxy;
import net.momirealms.sparrow.ui.util.VersionHelper;
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
     * 把五个协议阶段的包表读一遍, 建立只读索引.
     * <p>某个组合在当前版本没有包表时保持空表, 查它一律得到 -1.
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
     * @return 当前 ID; 注册名在当前版本不存在时为 -1, 调用方通常据此跳过注册
     */
    public int byName(@NotNull String name, @NotNull ConnectionState state, @NotNull PacketFlow flow) {
        return this.packetIds[state.ordinal()][flow.ordinal()].getOrDefault(name, -1);
    }

    /**
     * 该阶段该方向的 ID 空间长度, 也就是最大包 ID 加一.
     *
     * @param state 包所属协议阶段
     * @param flow 包的传输方向
     * @return 可以用来开定长路由数组的长度, 数组下标就是包 ID
     */
    public int count(@NotNull ConnectionState state, @NotNull PacketFlow flow) {
        return this.packetCounts[state.ordinal()][flow.ordinal()];
    }

    // 按 ID 排序打印, 出问题时拿来和 NMS 的实际包表对账
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
        // 1.21.5 起包表挂在 ProtocolInfo 的 details 上, 之前的版本直接把 unbound 模板交出去
        Class<?> visitorClass = VersionHelper.isOrAbove1_21_5
                ? ProtocolInfoDetailsProxy.PACKET_VISITOR_CLASS
                : ProtocolInfoUnboundProxy.PACKET_VISITOR_CLASS;
        if (visitorClass == null) {
            throw new IllegalStateException("Missing NMS packet visitor");
        }
        // 用一个动态代理实现 NMS 的包访问器, 免得每个版本都手写一份实现类; listPackets 回调的每个包都从 accept 进来
        Object visitor = Proxy.newProxyInstance(visitorClass.getClassLoader(), new Class<?>[]{visitorClass}, (proxy, method, arguments) -> {
            if (method.getName().equals("accept")) {
                Object packetType = arguments[0];
                int packetId = (int) arguments[1];
                String name = PacketTypeProxy.INSTANCE.id(packetType).toString();
                ids.put(name, packetId);
                largestId[0] = Math.max(largestId[0], packetId);
                return null;
            }
            // Object 的三个方法得自己给结果, 动态代理会把它们也转到 invoke 上来
            return switch (method.getName()) {
                case "toString" -> "SparrowUI packet table visitor";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new UnsupportedOperationException(method.toString());
            };
        });
        if (VersionHelper.isOrAbove1_21_5) {
            Object details = ProtocolInfoDetailsProviderProxy.INSTANCE.details(template);
            ProtocolInfoDetailsProxy.INSTANCE.listPackets(details, visitor);
        } else {
            ProtocolInfoUnboundProxy.INSTANCE.listPackets(template, visitor);
        }
        return new PacketTable(Map.copyOf(ids), largestId[0] + 1);
    }

    private record ProtocolTemplate(ConnectionState state, PacketFlow flow, Object template) {
    }

    private record PacketTable(Map<String, Integer> ids, int count) {
    }
}
