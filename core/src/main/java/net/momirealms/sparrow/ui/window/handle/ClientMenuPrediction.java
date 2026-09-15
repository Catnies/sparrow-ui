package net.momirealms.sparrow.ui.window.handle;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ServerboundContainerClickPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

/**
 * 客户端点击包声称的容器状态.
 * <p>旧版复制完整物品, 新版保存物品哈希, 两者均用于下一轮远端状态比较.
 */
@ApiStatus.Internal
public final class ClientMenuPrediction implements MenuPrediction {
    private static final int[] EMPTY_SLOTS = new int[0];
    private static final Object[] EMPTY_ITEMS = new Object[0];

    private final int[] changedSlots;
    private final Object[] changedItems; // NMS ItemStack[] 或 HashedStack[], 与 changedSlots 同下标对应
    private final Object cursor;          // NMS ItemStack 或 HashedStack 光标预测

    private ClientMenuPrediction(int @NotNull [] changedSlots, Object @NotNull [] changedItems, @NotNull Object cursor) {
        this.changedSlots = changedSlots;
        this.changedItems = changedItems;
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
        Object carried = proxy.carriedItem(packet);
        Object cursor = VersionHelper.isOrAbove1_21_5 ? carried : ItemStackProxy.INSTANCE.copy(carried);
        int size = changedSlots.size();
        if (size == 0) {
            return new ClientMenuPrediction(EMPTY_SLOTS, EMPTY_ITEMS, cursor);
        }
        int[] slots = new int[size];
        Object[] items = new Object[size];
        int index = 0;
        ObjectIterator<Int2ObjectMap.Entry<Object>> iterator = Int2ObjectMaps.fastIterator(changedSlots);
        while (iterator.hasNext()) {
            Int2ObjectMap.Entry<Object> entry = iterator.next();
            slots[index] = entry.getIntKey();
            items[index] = VersionHelper.isOrAbove1_21_5 ? entry.getValue() : ItemStackProxy.INSTANCE.copy(entry.getValue());
            index++;
        }
        return new ClientMenuPrediction(slots, items, cursor);
    }

    // 越界槽位来自无效客户端声明, 不进入服务端候选集合.
    Object apply(Object @NotNull [] remoteSlots, @NotNull Object remoteCursor, @NotNull BitSet candidates) {
        for (int index = 0; index < this.changedSlots.length; index++) {
            int slot = this.changedSlots[index];
            if (slot < 0 || slot >= remoteSlots.length) {
                continue;
            }
            remoteSlots[slot] = RemoteSlotUtils.receive(remoteSlots[slot], this.changedItems[index]);
            candidates.set(slot);
        }
        return RemoteSlotUtils.receive(remoteCursor, this.cursor);
    }
}
