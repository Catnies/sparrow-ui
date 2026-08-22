package net.momirealms.sparrow.ui.inventory.storage;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Bukkit 容器的外部存储适配, 读写直达被引用容器, 自身不持有任何内容状态.
 * <p>本类是认不出槽位排布时的兜底通道. 那种情况下看不出被引用容器背后是什么, 只好强持有它;
 * 那个容器背后要是连着世界里的东西, 本存储就会一直牵着那个世界, 得靠 retire 主动放手.
 * <p>存储槽位就是 Bukkit 容器槽位. {@code getContents} 与 {@code getStorageContents}
 * 都是容器槽位的前缀区段, 区段下标与 {@code getItem}/{@code setItem} 的槽位一致.
 */
@ApiStatus.Internal
public final class BukkitStorage implements ExternalStorage {
    private final Inventory bukkitInventory;                                 // 被引用的 Bukkit 容器
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
        // 玩家背包用主人的 UUID. 重生会换掉那个背包对象, 用主人身份当归属才能跨重生判等
        this.identity = this.owner == null ? bukkitInventory : this.owner.getUniqueId();
    }

    // 给一个 Bukkit Inventory 挑一种外部存储实现. 认得出槽位排布的直接读写背后的 NMS 容器, 认不出的回退到本类的 Bukkit 通道.
    @NotNull
    public static ExternalStorage of(@NotNull Inventory inventory, @NotNull Function<Inventory, @Nullable ItemStack[]> contentsGetter) {
        int size = contentsGetter.apply(inventory).length;
        ExternalStorage storage = BukkitInventoryLayout.storageOf(inventory, size);
        return storage != null ? storage : new BukkitStorage(inventory, contentsGetter);
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
