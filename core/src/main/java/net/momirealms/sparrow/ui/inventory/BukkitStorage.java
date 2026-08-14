package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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
    private final Object identity;           // SlotKey 的判等归属, 构造时定下

    private final @Nullable HumanEntity owner; // 玩家背包的主人, 其余容器为 null

    BukkitStorage(@NotNull Inventory bukkitInventory, @NotNull Function<Inventory, @Nullable ItemStack[]> contentsGetter) {
        this.bukkitInventory = bukkitInventory;
        this.contentsGetter = contentsGetter;
        this.owner = bukkitInventory instanceof PlayerInventory playerInventory ? playerInventory.getHolder() : null;
        this.size = contentsGetter.apply(bukkitInventory).length;
        this.bukkitMaxStackSize = bukkitInventory.getMaxStackSize();
        // 玩家背包用主人的 UUID: 重生会换掉那个背包对象, 拿它当归属重生前后就不判等了
        this.identity = this.owner == null ? bukkitInventory : this.owner.getUniqueId();
    }

    /**
     * 为 Bukkit Inventory 创建外部存储, 并根据情况挑选实现.
     * <p>走 NMS 的前提是被引用部分的槽号与 NMS 槽号一一对应, 否则同一个槽在两边指向不同位置.
     * 如果一个 Bukkit Inventory 背后不是 NMS 实现, 那么则回退在本类的 Bukkit 实现.
     *
     * @param inventory 被引用的 Bukkit Inventory
     * @param contentsGetter 从 Inventory 读取被引用区段的函数
     * @return 该容器的外部存储
     */
    @NotNull
    static ExternalStorage of(
            @NotNull Inventory inventory,
            @NotNull Function<Inventory, @Nullable ItemStack[]> contentsGetter
    ) {
        int size = contentsGetter.apply(inventory).length;
        // 特例: 为玩家背包创建外部存储.
        if (inventory instanceof PlayerInventory playerInventory) {
            HumanEntity owner = playerInventory.getHolder();
            if (owner == null || !ContainerStorage.SLOT_ALIGNED_PLAYER_INVENTORIES.contains(inventory.getClass()) || size != inventory.getStorageContents().length) {
                return new BukkitStorage(inventory, contentsGetter);
            }
            return new ContainerStorage.OfPlayer(owner, size);
        }
        // 特例: 只有 Bukkit 实现和 NMS 的槽号一一对应的才可以走 NMS 实现.
        if (ContainerStorage.SLOT_ALIGNED_INVENTORIES.contains(inventory.getClass())) {
            return new ContainerStorage.Fixed(CraftInventoryProxy.INSTANCE.getInventory(inventory));
        }
        // 其余走 Bukkit 回退实现.
        return new BukkitStorage(inventory, contentsGetter);
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
    public SlotKey keyOf(int slot) {
        return new SlotKey(this.identity, slot);
    }

    @Override
    public boolean alive() {
        return !(this.owner instanceof Player player) || player.isConnected();
    }
}
