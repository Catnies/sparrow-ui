package net.momirealms.sparrow.ui.internal.menu;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.RemoteSlot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

/**
 * 客户端点击包携带的非权威容器预测.
 *
 * <p>此对象只把客户端声称的远端哈希转交给 Paper 的 {@link RemoteSlot}. Window 的物品数组始终是
 * 权威状态, 预测值只能缩小需要复核的槽位集合, 不能直接改变任何业务物品.</p>
 */
@ApiStatus.Internal
public final class ClientMenuPrediction implements MenuPrediction {
    private final Int2ObjectMap<HashedStack> changedSlots;
    private final HashedStack cursor;

    private ClientMenuPrediction(
            @NotNull Int2ObjectMap<HashedStack> changedSlots,
            @NotNull HashedStack cursor
    ) {
        this.changedSlots = Int2ObjectMaps.unmodifiable(new Int2ObjectOpenHashMap<>(changedSlots));
        this.cursor = cursor;
    }

    /**
     * 从不再向下游转发的点击包接管预测数据.
     *
     * <p>点击包在 Netty 线程解码, 预测会在玩家实体线程消费. 这里复制可变 fastutil map, 避免
     * 依赖 NMS 包在跨线程排队期间保持只读.</p>
     *
     * @param packet 已被 Sparrow 捕获的点击包
     * @return 该包的非权威预测
     */
    public static @NotNull ClientMenuPrediction from(@NotNull ServerboundContainerClickPacket packet) {
        return new ClientMenuPrediction(packet.changedSlots(), packet.carriedItem());
    }

    /**
     * 将预测写入远端镜像, 并记录后续必须与权威状态核对的槽位.
     *
     * @param remoteSlots Paper 远端槽位镜像
     * @param remoteCursor Paper 远端光标镜像
     * @param candidates 待复核槽位集合
     * @return 是否携带了需要复核的光标预测
     */
    boolean apply(
            RemoteSlot @NotNull [] remoteSlots,
            @NotNull RemoteSlot remoteCursor,
            @NotNull BitSet candidates
    ) {
        for (Int2ObjectMap.Entry<HashedStack> entry : this.changedSlots.int2ObjectEntrySet()) {
            int slot = entry.getIntKey();
            if (slot < 0 || slot >= remoteSlots.length) {
                continue;
            }
            remoteSlots[slot].receive(entry.getValue());
            candidates.set(slot);
        }
        remoteCursor.receive(this.cursor);
        return true;
    }
}
