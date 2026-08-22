package net.momirealms.sparrow.ui.inventory.storage;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

// 坐骑背包的一段. 鞍和护甲那两格每次从坐骑取出来都是新的, 主仓则是坐骑自己那个背包,
// 三段各按各的容器读写, 归属统一挂在坐骑身上, 用它在整条背包里的位置区分.
final class MountContainerStorage extends FixedContainerStorage {
    private final UUID mount;         // 这一段属于哪只坐骑
    private final int firstMountSlot; // 本段在坐骑背包里的起始槽号

    MountContainerStorage(@NotNull Object container, @NotNull UUID mount, int firstMountSlot) {
        super(container);
        this.mount = mount;
        this.firstMountSlot = firstMountSlot;
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        // 槽号取它在坐骑背包里的位置, 这样同一只坐骑的几段各算各的
        return new SlotKey(this.mount, this.firstMountSlot + slot);
    }
}
