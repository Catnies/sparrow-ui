package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Function;

/**
 * Bukkit 容器的外部存储适配, 读写直达被引用容器, 自身不持有任何内容状态.
 * <p>存储槽位就是 Bukkit 容器槽位. {@code getContents} 与 {@code getStorageContents}
 * 都是容器槽位的前缀区段, 区段下标与 {@code getItem}/{@code setItem} 的槽位一致.
 * <p>它同时兼任工厂, {@link #of} 负责在自己这条 Bukkit 通道和更快的 NMS 通道之间挑一个.
 */
@ApiStatus.Internal
public final class BukkitStorage implements ExternalStorage {
    // Bukkit 槽号与 getInventory() 给出的 NMS 容器槽号一一对应的 CraftInventory 实现.
    // 已知的三种错位形态都被挡在表外. CraftInventoryCrafting 把结果格排在 3x3 合成格前面, 而 getInventory() 只有那 9 格;
    // CraftInventorySaddledMount 及其子类把鞍与护甲排在主仓前面, 两者还各自是独立的 NMS 容器;
    // CraftInventoryPlayer 的装备槽在 NMS 背包里另有一套映射, 由下面那张表单独特例处理.
    private static final Set<Class<?>> SLOT_ALIGNED_INVENTORIES = BukkitProxy.findClasses(
            "org.bukkit.craftbukkit.inventory.CraftInventory",
            "org.bukkit.craftbukkit.inventory.CraftInventoryBeacon",
            "org.bukkit.craftbukkit.inventory.CraftInventoryBrewer",
            "org.bukkit.craftbukkit.inventory.CraftInventoryChiseledBookshelf",
            "org.bukkit.craftbukkit.inventory.CraftInventoryCustom",
            "org.bukkit.craftbukkit.inventory.CraftInventoryDecoratedPot",
            "org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest",
            "org.bukkit.craftbukkit.inventory.CraftInventoryEnchanting",
            "org.bukkit.craftbukkit.inventory.CraftInventoryFurnace",
            "org.bukkit.craftbukkit.inventory.CraftInventoryJukebox",
            "org.bukkit.craftbukkit.inventory.CraftInventoryLectern",
            "org.bukkit.craftbukkit.inventory.CraftInventoryMerchant",
            "org.bukkit.craftbukkit.inventory.CraftInventoryShelf"
    );
    // 存储区段与 NMS 背包槽号一一对应的玩家背包实现.
    private static final Set<Class<?>> SLOT_ALIGNED_PLAYER_INVENTORIES = BukkitProxy.findClasses(
            "org.bukkit.craftbukkit.inventory.CraftInventoryPlayer"
    );

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

    // 给一个 Bukkit Inventory 挑一种外部存储实现. 能走 NMS 的前提是被引用区段的槽号与 NMS 槽号一一对应,
    // 背后压根不是 NMS 实现的回退到本类的 Bukkit 通道.
    @NotNull
    public static ExternalStorage of(
            @NotNull Inventory inventory,
            @NotNull Function<Inventory, @Nullable ItemStack[]> contentsGetter
    ) {
        int size = contentsGetter.apply(inventory).length;
        // 玩家背包走这一支, 尺寸对不上说明这一段带了装备槽, 那些槽在 NMS 背包里另有映射, 只能走 Bukkit 通道.
        if (inventory instanceof PlayerInventory playerInventory) {
            HumanEntity owner = playerInventory.getHolder();
            if (owner == null || !SLOT_ALIGNED_PLAYER_INVENTORIES.contains(inventory.getClass()) || size != inventory.getStorageContents().length) {
                return new BukkitStorage(inventory, contentsGetter);
            }
            return new PlayerContainerStorage(owner, size);
        }
        // 只有 Bukkit 实现和 NMS 的槽号一一对应的才可以走 NMS 实现.
        if (SLOT_ALIGNED_INVENTORIES.contains(inventory.getClass())) {
            return new FixedContainerStorage(CraftInventoryProxy.INSTANCE.getInventory(inventory));
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
