package net.momirealms.sparrow.ui.inventory.storage;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

// 坐骑背包的一个分段, 以坐骑身份和全局槽号连接鞍位, 护甲位与主仓.
final class MountContainerStorage extends FixedContainerStorage {
    private final UUID mount;
    private final int firstMountSlot;

    MountContainerStorage(@NotNull Object container, @NotNull UUID mount, int firstMountSlot) {
        super(container);
        this.mount = mount;
        this.firstMountSlot = firstMountSlot;
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        return new SlotKey(this.mount, this.firstMountSlot + slot);
    }
}
