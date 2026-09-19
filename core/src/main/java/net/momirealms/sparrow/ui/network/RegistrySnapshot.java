package net.momirealms.sparrow.ui.network;

import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketFlow;
import net.momirealms.sparrow.ui.network.packet.PacketType;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;

record RegistrySnapshot(PacketEntry<ByteBufPacketHandler>[][][][] byteBuf, IdentityHashMap<Object, PacketEntry<NMSPacketHandler>[]>[][] nms) {
    @SuppressWarnings("unchecked")
    static final PacketEntry<ByteBufPacketHandler>[] EMPTY_BYTE_BUF = new PacketEntry[0];
    @SuppressWarnings("unchecked")
    static final PacketEntry<NMSPacketHandler>[] EMPTY_NMS = new PacketEntry[0];

    @SuppressWarnings("unchecked")
    static RegistrySnapshot empty() {
        // 按方向、阶段选择身份表, 表内直接保存监听数组. 发布后的 Map 和数组都只供读取.
        IdentityHashMap<Object, PacketEntry<NMSPacketHandler>[]>[][] nms = new IdentityHashMap[PacketFlow.values().length][ConnectionState.values().length];
        for (int flow = 0; flow < nms.length; flow++) {
            for (int state = 0; state < nms[flow].length; state++) {
                nms[flow][state] = new IdentityHashMap<>();
            }
        }
        return new RegistrySnapshot(new PacketEntry[PacketFlow.values().length][ConnectionState.values().length][][], nms);
    }

    @SuppressWarnings("unchecked")
    RegistrySnapshot withByteBuf(PacketType type, int id, int size, PacketEntry<ByteBufPacketHandler> @Nullable [] entries) {
        int flow = type.flow().ordinal();
        int state = type.state().ordinal();
        // 只复制目标方向、阶段行及其父数组, 其余路由与 NMS 表继续共享旧快照中的只读对象.
        PacketEntry<ByteBufPacketHandler>[][][][] updated = this.byteBuf.clone();
        updated[flow] = updated[flow].clone();
        PacketEntry<ByteBufPacketHandler>[][] row = updated[flow][state];
        row = row == null ? new PacketEntry[size][] : row.clone();
        row[id] = entries;
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

    RegistrySnapshot withNms(PacketType type, Object nativeType, PacketEntry<NMSPacketHandler> @Nullable [] entries) {
        int flow = type.flow().ordinal();
        int state = type.state().ordinal();
        // 只复制目标阶段的身份表和父数组, 其他方向、阶段继续共享旧快照.
        IdentityHashMap<Object, PacketEntry<NMSPacketHandler>[]>[][] updated = this.nms.clone();
        updated[flow] = updated[flow].clone();
        updated[flow][state] = new IdentityHashMap<>(updated[flow][state]);
        if (entries != null) {
            updated[flow][state].put(nativeType, entries);
        } else {
            updated[flow][state].remove(nativeType);
        }
        return new RegistrySnapshot(this.byteBuf, updated);
    }
}
