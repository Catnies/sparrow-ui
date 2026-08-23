package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.UUID;

// 每次访问都从玩家实体取得当前背包, 以适配重生后的背包替换.
final class PlayerContainerStorage extends ContainerStorage {
    private final WeakReference<HumanEntity> owner;
    private final UUID ownerId; // 背包对象变化时槽位身份仍保持稳定

    PlayerContainerStorage(@NotNull HumanEntity owner, int size) {
        super(size, ContainerProxy.INSTANCE.getMaxStackSize(containerOf(owner)));
        this.owner = new WeakReference<>(owner);
        this.ownerId = owner.getUniqueId();
    }

    @Override
    @Nullable
    Object container() {
        HumanEntity owner = this.owner.get();
        return owner == null ? null : containerOf(owner);
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        return new SlotKey(this.ownerId, slot);
    }

    @Override
    public boolean alive() {
        HumanEntity owner = this.owner.get();
        return owner != null && (!(owner instanceof Player player) || player.isConnected());
    }

    // getInventory 读的是玩家实体上那个字段, 重生换过背包之后它给出的就是新的那一个.
    @NotNull
    private static Object containerOf(@NotNull HumanEntity owner) {
        return CraftInventoryProxy.INSTANCE.getInventory(owner.getInventory());
    }
}
