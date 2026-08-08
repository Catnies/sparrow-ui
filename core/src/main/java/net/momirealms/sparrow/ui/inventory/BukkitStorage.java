package net.momirealms.sparrow.ui.inventory;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Bukkit 容器的外部存储适配: 读写直达被引用容器, 自身不持有任何内容状态.
 * <p>存储槽位就是 Bukkit 容器槽位: {@code getContents} 与 {@code getStorageContents}
 * 都是容器槽位的前缀区段, 区段下标与 {@code getItem}/{@code setItem} 的槽位一致.
 */
final class BukkitStorage implements ExternalStorage {
    private final Inventory bukkitInventory; // 被引用的 Bukkit 容器
    private final Function<Inventory, @Nullable ItemStack[]> contentsGetter; // 读取被引用区段(getContents / getStorageContents)
    private final int size;                  // 被引用区段的槽位数量, 构造时取样
    private final int bukkitMaxStackSize;    // 容器的堆叠上限, 构造时缓存

    BukkitStorage(@NotNull Inventory bukkitInventory, @NotNull Function<Inventory, @Nullable ItemStack[]> contentsGetter) {
        this.bukkitInventory = bukkitInventory;
        this.contentsGetter = contentsGetter;
        this.size = contentsGetter.apply(bukkitInventory).length;
        this.bukkitMaxStackSize = bukkitInventory.getMaxStackSize();
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    @Nullable
    public ItemStack read(int slot) {
        return this.bukkitInventory.getItem(slot);
    }

    @Override
    public @Nullable ItemStack @NotNull [] readAll() {
        return this.contentsGetter.apply(this.bukkitInventory);
    }

    @Override
    public void write(int slot, @Nullable ItemStack item) {
        this.bukkitInventory.setItem(slot, item);
    }

    @Override
    public int maxStackSize(int slot) {
        return this.bukkitMaxStackSize;
    }

    @Override
    @NotNull
    public Object identity() {
        return this.bukkitInventory;
    }
}
