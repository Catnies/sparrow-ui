package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import org.bukkit.inventory.Inventory;

/**
 * 直达 CraftInventory 背后 NMS Container 的写通道.
 * <p>Bukkit 的 {@code Inventory#setItem} 必定复制物品(asNMSCopy), 原版语义的指针转移只能在
 * NMS 层完成. 目标类缺失时(测试环境, 非 CraftBukkit 平台)探测降级为"无法直达", 不是错误,
 * 调用方应改用 Bukkit 写入.
 */
public final class CraftContainerAccess {
    private static final Class<?> CRAFT_INVENTORY_CLASS = SparrowClass.find("org.bukkit.craftbukkit.inventory.CraftInventory");

    private CraftContainerAccess() {
    }

    /**
     * 判断给定 Bukkit 容器是否背靠可直达的 NMS Container.
     * <p>工作台, 铁砧这类视图由多个 NMS Container 拼成, Bukkit 层负责槽号分派, 而 {@code getInventory()}
     * 只返回其中一个 —— 对它裸索引直写会写错格或越界. 因此尺寸与 Bukkit 视图对不上的容器一律判定为不可直达,
     * 回写自动退回 Bukkit 写入.
     *
     * @param inventory 待探测的 Bukkit 容器
     * @return 可以通过 {@link #setItem} 零拷贝写入时返回 {@code true}
     */
    public static boolean isCraftBacked(Inventory inventory) {
        // todo 生产必过, 多余判断, 自动清理
        if (CRAFT_INVENTORY_CLASS == null || !CRAFT_INVENTORY_CLASS.isInstance(inventory)) {
            return false;
        }
        return ContainerProxy.INSTANCE.getContainerSize(CraftInventoryProxy.INSTANCE.getInventory(inventory)) == inventory.getSize();
    }

    /**
     * 把 NMS 物品实例原样写进容器槽位, 不复制.
     * <p>调用方必须先用 {@link #isCraftBacked} 确认容器可直达, 并在交出句柄后不再持有或修改它.
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
