package net.momirealms.sparrow.ui.internal.menu;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ServerboundContainerClickPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.RemoteSlotProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

/**
 * 客户端点击包携带的容器预测, 只代表客户端声称的状态.
 * <p>此对象只把客户端声称的哈希交给 Paper 的 RemoteSlot 客户端已知状态. Window 槽位内容始终
 * 由服务端决定; 预测值只能缩小需要复核的槽位集合, 不能直接改变任何业务物品.</p>
 */
@ApiStatus.Internal
public final class ClientMenuPrediction implements MenuPrediction {
    private static final int[] EMPTY_SLOTS = new int[0];
    private static final Object[] EMPTY_HASHES = new Object[0];

    private final int[] changedSlots;
    private final Object[] changedHashes; // NMS HashedStack 数组副本, 与 changedSlots 按索引对应
    private final Object cursor; // NMS HashedStack 光标预测

    private ClientMenuPrediction(int @NotNull [] changedSlots, Object @NotNull [] changedHashes, @NotNull Object cursor) {
        this.changedSlots = changedSlots;
        this.changedHashes = changedHashes;
        this.cursor = cursor;
    }

    /**
     * 从不再向下游转发的点击包接管预测数据.
     *
     * <p>点击包在 Netty 线程解码, 预测会在玩家实体线程消费. 这里把可变 fastutil map 压缩为
     * 两个顺序数组, 在保持跨线程输入稳定的同时避免为一次性遍历复制哈希表和包装器.</p>
     *
     * @param packet 已被 Sparrow 捕获的点击包
     * @return 该包携带的客户端预测状态
     */
    @NotNull
    public static ClientMenuPrediction from(@NotNull Object packet) {
        ServerboundContainerClickPacketProxy proxy = ServerboundContainerClickPacketProxy.INSTANCE;
        Int2ObjectMap<Object> changedSlots = proxy.changedSlots(packet);
        int size = changedSlots.size();
        if (size == 0) {
            return new ClientMenuPrediction(EMPTY_SLOTS, EMPTY_HASHES, proxy.carriedItem(packet));
        }
        int[] slots = new int[size];
        Object[] hashes = new Object[size]; // NMS HashedStack[]
        int index = 0;
        ObjectIterator<Int2ObjectMap.Entry<Object>> iterator = Int2ObjectMaps.fastIterator(changedSlots);
        while (iterator.hasNext()) {
            Int2ObjectMap.Entry<Object> entry = iterator.next();
            slots[index] = entry.getIntKey();
            hashes[index] = entry.getValue();
            index++;
        }
        return new ClientMenuPrediction(slots, hashes, proxy.carriedItem(packet));
    }

    /**
     * 将预测写入客户端已知状态, 并记录后续必须与服务端槽位内容核对的槽位.
     *
     * @param remoteSlots Paper 槽位客户端已知状态
     * @param remoteCursor Paper 光标客户端已知状态
     * @param candidates 待复核槽位集合
     * @return 是否携带了需要复核的光标预测
     */
    boolean apply(Object @NotNull [] remoteSlots, @NotNull Object remoteCursor, @NotNull BitSet candidates) {
        for (int index = 0; index < this.changedSlots.length; index++) {
            int slot = this.changedSlots[index];
            if (slot < 0 || slot >= remoteSlots.length) {
                continue;
            }
            RemoteSlotProxy.INSTANCE.receive(remoteSlots[slot], this.changedHashes[index]);
            candidates.set(slot);
        }
        RemoteSlotProxy.INSTANCE.receive(remoteCursor, this.cursor);
        return true;
    }
}
