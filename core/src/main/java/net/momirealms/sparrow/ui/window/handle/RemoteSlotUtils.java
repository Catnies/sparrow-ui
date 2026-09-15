package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.RemoteSlotProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.VersionHelper;

// 旧版远端状态是物品快照, 新版是 NMS RemoteSlot.
final class RemoteSlotUtils {
    private RemoteSlotUtils() {
    }

    // 发送后保存独立快照, 返回本次提交后的远端状态.
    static Object force(Object remote, Object item) {
        if (VersionHelper.isOrAbove1_21_5) {
            RemoteSlotProxy.INSTANCE.force(remote, item);
            return remote;
        }
        return ItemStackProxy.INSTANCE.copy(item);
    }

    // 旧版预测物品已在入站捕获时复制, 此处接收该快照的所有权.
    static Object receive(Object remote, Object prediction) {
        if (VersionHelper.isOrAbove1_21_5) {
            RemoteSlotProxy.INSTANCE.receive(remote, prediction);
            return remote;
        }
        return prediction;
    }

    static boolean matches(Object remote, Object item) {
        return VersionHelper.isOrAbove1_21_5
                ? RemoteSlotProxy.INSTANCE.matches(remote, item)
                : ItemStackProxy.INSTANCE.matches(remote, item);
    }
}
