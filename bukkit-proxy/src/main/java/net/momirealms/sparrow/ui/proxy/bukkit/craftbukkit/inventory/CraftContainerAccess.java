package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import org.bukkit.inventory.Inventory;

/**
 * 直达 CraftInventory 背后 NMS Container 的写通道.
 * <p>Bukkit 的 {@code Inventory#setItem} 必定复制物品(asNMSCopy), 原版语义的指针转移只能在NMS 层完成.
 */
public final class CraftContainerAccess {
    private CraftContainerAccess() {
    }

    /**
     * 把 NMS 物品实例原样写进容器槽位, 不复制.
     * <p>调用方在交出句柄后不得再持有或修改它.
     *
     * @param inventory 背靠 NMS Container 的 Bukkit 容器
     * @param slot NMS 容器槽位
     * @param itemHandle NMS {@code ItemStack} 实例
     */
    public static void setItem(Inventory inventory, int slot, Object itemHandle) {
        ContainerProxy.INSTANCE.setItem(CraftInventoryProxy.INSTANCE.getInventory(inventory), slot, itemHandle);
    }

    /**
     * 通知 NMS Container 内容已经变化.
     * <p>原地修改物品数量绕过了容器自己的写入口, 方块实体不会自动标脏; 改完补一次 setChanged,
     * 存档语义才与原版 grow/shrink 之后的 {@code slot.setChanged()} 一致.
     *
     * @param inventory 背靠 NMS Container 的 Bukkit 容器
     */
    public static void markChanged(Inventory inventory) {
        ContainerProxy.INSTANCE.setChanged(CraftInventoryProxy.INSTANCE.getInventory(inventory));
    }
}
