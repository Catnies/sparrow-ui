package net.momirealms.sparrow.ui.window.handle;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ServerboundContainerClickPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.RemoteSlotProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

/**
 * 客户端点击包声称的容器状态.
 * <p>预测哈希写入 Paper RemoteSlot 后参与差异检查, Window 物品仍由服务端结果决定.
 */
@ApiStatus.Internal
public final class ClientMenuPrediction implements MenuPrediction {
    private static final int[] EMPTY_SLOTS = new int[0];
    private static final Object[] EMPTY_HASHES = new Object[0];

    private final int[] changedSlots;
    private final Object[] changedHashes; // NMS HashedStack[], 与 changedSlots 同下标对应
    private final Object cursor;          // NMS HashedStack 光标预测

    private ClientMenuPrediction(int @NotNull [] changedSlots, Object @NotNull [] changedHashes, @NotNull Object cursor) {
        this.changedSlots = changedSlots;
        this.changedHashes = changedHashes;
        this.cursor = cursor;
    }

    /**
     * 在 Netty 线程将点击包压缩为实体线程读取的稳定快照.
     *
     * @param packet NMS ServerboundContainerClickPacket
     * @return 独立的客户端预测快照
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

    // 越界槽位来自无效客户端声明, 不进入服务端候选集合.
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
