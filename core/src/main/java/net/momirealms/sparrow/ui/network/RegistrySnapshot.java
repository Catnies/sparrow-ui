package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;

record RegistrySnapshot(ByteBufRoute[][][] byteBuf, IdentityHashMap<Object, NmsTypeRoutes>[] nms) {
    @SuppressWarnings("unchecked")
    static final PacketEntry<ByteBufPacketHandler>[] EMPTY_BYTE_BUF = new PacketEntry[0];
    @SuppressWarnings("unchecked")
    static final PacketEntry<NMSPacketHandler>[] EMPTY_NMS = new PacketEntry[0];

    @SuppressWarnings("unchecked")
    static RegistrySnapshot empty() {
        // 每个方向一张原生类型身份表, 发布后的 Map 和各层数组都只供读取.
        IdentityHashMap<Object, NmsTypeRoutes>[] nms = new IdentityHashMap[PacketFlow.values().length];
        for (int index = 0; index < nms.length; index++) {
            nms[index] = new IdentityHashMap<>();
        }
        return new RegistrySnapshot(new ByteBufRoute[PacketFlow.values().length][ConnectionState.values().length][], nms);
    }

    RegistrySnapshot withByteBuf(PacketType type, int id, int size, @Nullable ByteBufRoute route) {
        int flow = type.flow().ordinal();
        int state = type.state().ordinal();
        // 只复制目标方向、阶段行及其父数组, 其余路由与 NMS 表继续共享旧快照中的只读对象.
        ByteBufRoute[][][] updated = this.byteBuf.clone();
        updated[flow] = updated[flow].clone();
        ByteBufRoute[] row = updated[flow][state];
        row = row == null ? new ByteBufRoute[size] : row.clone();
        row[id] = route;
        // 空阶段用 null 表示, 派发可以在读取包 ID 前直接放行.
        boolean active = false;
        for (int index = 0; index < row.length; index++) {
            if (row[index] != null) {
                active = true;
                break;
            }
        }
        updated[flow][state] = active ? row : null;
        return new RegistrySnapshot(updated, this.nms);
    }

    RegistrySnapshot withNms(PacketType type, Object nativeType, int stateMask, @Nullable NmsRoute route) {
        int flow = type.flow().ordinal();
        // 保留原生 PacketType 的对象身份语义, 为当前方向复制身份表, 再复制命中类型的阶段数组.
        IdentityHashMap<Object, NmsTypeRoutes>[] updated = this.nms.clone();
        updated[flow] = new IdentityHashMap<>(updated[flow]);
        NmsTypeRoutes previous = updated[flow].get(nativeType);
        NmsRoute[] routes = previous == null ? new NmsRoute[ConnectionState.values().length] : previous.routes().clone();
        routes[type.state().ordinal()] = route;
        boolean active = false;
        for (int index = 0; index < routes.length; index++) {
            if (routes[index] != null) {
                active = true;
                break;
            }
        }
        if (active) {
            // 来源位集由完整原版表生成. 只有一个来源阶段的类型在用户状态推进后仍按原阶段匹配.
            // -1 表示类型跨阶段共用, 派发时使用根包进入对象桥时捕获的阶段.
            int fixedState = Integer.bitCount(stateMask) == 1 ? Integer.numberOfTrailingZeros(stateMask) : -1;
            updated[flow].put(nativeType, new NmsTypeRoutes(fixedState, routes));
        } else {
            // 该原生类型的全部阶段都已注销; 移除后可以恢复方向表为空时的直通路径.
            updated[flow].remove(nativeType);
        }
        return new RegistrySnapshot(this.byteBuf, updated);
    }

    record NmsTypeRoutes(int fixedState, NmsRoute[] routes) {
    }

    record NmsRoute(PacketType type, PacketEntry<NMSPacketHandler>[] entries) {
    }

    record ByteBufRoute(PacketType type, PacketEntry<ByteBufPacketHandler>[] entries) {
    }
}
