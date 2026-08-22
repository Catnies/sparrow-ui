package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

// Bukkit 槽号与 NMS 背包槽号一一对应, 前面是主背包与快捷栏, 后面那几格由 NMS 背包自己换算到装备上.
final class PlayerContainerStorage extends ContainerStorage {
    private final HumanEntity owner; // 背包主人, 跨死亡重生稳定

    PlayerContainerStorage(@NotNull HumanEntity owner, int size) {
        super(size, ContainerProxy.INSTANCE.getMaxStackSize(containerOf(owner)));
        this.owner = owner;
    }

    @Override
    @NotNull
    Object container() {
        return containerOf(this.owner);
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        // 归属跟着玩家走. 重生换掉的是背包, 用玩家 UUID 当归属才能跨重生判等
        return new SlotKey(this.owner.getUniqueId(), slot);
    }

    @Override
    public boolean alive() {
        return !(this.owner instanceof Player player) || player.isConnected();
    }

    // getInventory 读的是玩家实体上那个字段, 重生换过背包之后它给出的就是新的那一个.
    @NotNull
    private static Object containerOf(@NotNull HumanEntity owner) {
        return CraftInventoryProxy.INSTANCE.getInventory(owner.getInventory());
    }
}
