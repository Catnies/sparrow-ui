package net.momirealms.sparrow.ui.util;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ItemUtils {

    private ItemUtils() {
    }

    /**
     * 返回可独立修改的 Bukkit 快照, 并规范化缺失或空物品.
     *
     * @param source 源物品堆, 或 {@code null}
     * @return 归调用方所有的物品堆快照
     */
    @NotNull
    public static ItemStack copyOrEmpty(@Nullable ItemStack source) {
        if (source == null || source.isEmpty()) {
            return ItemStack.empty();
        }
        return source.clone();
    }

    /**
     * 从Bukkit ItemStack 获取 NMS ItemStack.
     * <p>返回值仍由传入的 Bukkit 物品持有.
     *
     * @param item Bukkit 物品快照
     * @return 借用的底层物品句柄
     */
    @NotNull
    public static Object getItemStackNMSHandle(@NotNull ItemStack item) {
        if (item.isEmpty()) {
            return ItemStackProxy.INSTANCE.empty();
        }
        return CraftItemStackProxy.INSTANCE.unwrap(item);
    }

    /**
     * 通过 Bukkit Proxy 创建可独立持有的底层物品快照.
     *
     * @param itemStackNMSHandle 借用的底层物品句柄
     * @return 可独立持有的底层物品句柄
     */
    @NotNull
    public static Object copyNMSItemStack(@NotNull Object itemStackNMSHandle) {
        return ItemStackProxy.INSTANCE.copy(itemStackNMSHandle);
    }
}
