package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

// 坐骑的鞍或护甲那一格. 这种容器每次从坐骑取出来都是新的, 归属只好挂在坐骑自己身上.
// 存活由同一条背包里的主仓那一段说了算.
final class MountEquipmentStorage extends ContainerStorage {
    private final Object container; // 取出来的那一格容器
    private final UUID mount;       // 这一格属于哪只坐骑
    private final int mountSlot;    // 这一格在坐骑背包里的槽号, 鞍与护甲靠它区分

    MountEquipmentStorage(@NotNull Object container, @NotNull UUID mount, int mountSlot) {
        super(ContainerProxy.INSTANCE.getContainerSize(container), ContainerProxy.INSTANCE.getMaxStackSize(container));
        this.container = container;
        this.mount = mount;
        this.mountSlot = mountSlot;
    }

    @Override
    @NotNull
    Object container() {
        return this.container;
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        // 这一段只有一格, 槽号取它在坐骑背包里的位置
        return new SlotKey(this.mount, this.mountSlot);
    }
}
