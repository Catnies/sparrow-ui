package net.momirealms.sparrow.ui.network.packet;

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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PacketIdRegistry {
    private final PacketType[][][] packetTypes;
    private final List<PacketType>[][] availableTypes;
    private final Map<String, Integer>[][] packetIds;
    private final Map<String, Object>[][] nativeTypes;

    /**
     * 把五个协议阶段的包表读一遍, 建立只读索引.
     * <p>某个阶段和方向在当前版本没有包表时保持空表.
     */
    @SuppressWarnings("unchecked")
    public PacketIdRegistry() {
        this.packetIds = (Map<String, Integer>[][]) new Map[ConnectionState.values().length][PacketFlow.values().length];
        this.nativeTypes = (Map<String, Object>[][]) new Map[ConnectionState.values().length][PacketFlow.values().length];
        this.packetTypes = new PacketType[ConnectionState.values().length][PacketFlow.values().length][];
        this.availableTypes = (List<PacketType>[][]) new List[ConnectionState.values().length][PacketFlow.values().length];
        for (ConnectionState state : ConnectionState.values()) {
            for (PacketFlow flow : PacketFlow.values()) {
                this.packetIds[state.ordinal()][flow.ordinal()] = Map.of();
                this.nativeTypes[state.ordinal()][flow.ordinal()] = Map.of();
                this.packetTypes[state.ordinal()][flow.ordinal()] = new PacketType[0];
                this.availableTypes[state.ordinal()][flow.ordinal()] = List.of();
            }
        }
        for (ProtocolTemplate template : protocolTemplates()) {
            PacketTable table = readPacketTable(template.template());
            this.packetIds[template.state().ordinal()][template.flow().ordinal()] = table.packetIds();
            this.nativeTypes[template.state().ordinal()][template.flow().ordinal()] = table.nativeTypes();
            PacketType[] types = new PacketType[table.count()];
            table.packetIds().forEach((name, id) -> types[id] = new PacketType(name, template.state(), template.flow()));
            List<PacketType> available = new ArrayList<>(table.packetIds().size());
            for (int id = 0; id < types.length; id++) {
                if (types[id] != null) {
                    available.add(types[id]);
                }
            }
            this.packetTypes[template.state().ordinal()][template.flow().ordinal()] = types;
            this.availableTypes[template.state().ordinal()][template.flow().ordinal()] = List.copyOf(available);
        }
    }

    /**
     * 按完整注册名查询当前服务端的包类型.
     *
     * @param name 完整注册名, 例如 {@code minecraft:container_click}
     * @param state 包所属协议阶段
     * @param flow 包的传输方向
     * @return 当前包表中的描述符; 不存在时为 null
     */
    @Nullable
    public PacketType find(@NotNull String name, @NotNull ConnectionState state, @NotNull PacketFlow flow) {
        return this.type(this.byName(name, state, flow), state, flow);
    }

    /**
     * 按当前服务端的数字 ID 反查包类型.
     *
     * @param id 包 ID
     * @param state 包所属协议阶段
     * @param flow 包的传输方向
     * @return 当前包表中的描述符; ID 为负数、越界或对应位置没有包时为 null
     */
    @Nullable
    public PacketType type(int id, @NotNull ConnectionState state, @NotNull PacketFlow flow) {
        PacketType[] types = this.packetTypes[state.ordinal()][flow.ordinal()];
        return id >= 0 && id < types.length ? types[id] : null;
    }

    /**
     * 列出当前服务端指定阶段和方向的线协议包类型, 按数字 ID 升序排列.
     *
     * @param state 包所属协议阶段
     * @param flow 包的传输方向
     * @return 初始化时创建的只读列表; 没有包时为空列表
     */
    @NotNull
    public List<PacketType> types(@NotNull ConnectionState state, @NotNull PacketFlow flow) {
        return this.availableTypes[state.ordinal()][flow.ordinal()];
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
     * 按逻辑包类型查询当前服务端的数字 ID.
     *
     * @param type 包的注册名, 阶段和方向
     * @return 当前数字 ID; 当前版本不存在该类型时为 -1
     */
    public int id(@NotNull PacketType type) {
        return this.byName(type.name(), type.state(), type.flow());
    }

    /**
     * 根据 PacketType 获取 NMS 的 PacketType.
     * @param type
     * @return
     */
    @Nullable
    public Object nativeType(@NotNull PacketType type) {
        return this.nativeTypes[type.state().ordinal()][type.flow().ordinal()].get(type.name());
    }

    /**
     * 该阶段该方向的 ID 空间长度, 也就是最大包 ID 加一.
     *
     * @param state 包所属协议阶段
     * @param flow 包的传输方向
     * @return 可以用来开定长路由数组的长度, 数组下标就是包 ID
     */
    public int count(@NotNull ConnectionState state, @NotNull PacketFlow flow) {
        return this.packetTypes[state.ordinal()][flow.ordinal()].length;
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
        HashMap<String, Integer> packetIds = new HashMap<>();
        HashMap<String, Object> nativeTypes = new HashMap<>();
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
                packetIds.put(name, packetId);
                nativeTypes.put(name, packetType);
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
        return new PacketTable(Map.copyOf(packetIds), Map.copyOf(nativeTypes), largestId[0] + 1);
    }

    private record ProtocolTemplate(ConnectionState state, PacketFlow flow, Object template) {
    }

    private record PacketTable(Map<String, Integer> packetIds, Map<String, Object> nativeTypes, int count) {
    }
}
