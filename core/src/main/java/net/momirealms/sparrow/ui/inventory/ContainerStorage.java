package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * NMS 容器的外部存储适配: 读写直达被引用容器背后的 NMS 容器.
 */
abstract class ContainerStorage implements ExternalStorage {
    /**
     * Bukkit 槽号与 {@code getInventory()} 给出的 NMS 容器槽号一一对应的 CraftInventory 实现.
     * <p>已知的三种错位形态都不在表里:
     * <ul>
     *   <li>{@code CraftInventoryCrafting} 把结果格排在 3x3 合成格前面, 而 {@code getInventory()} 只有那 9 格;
     *   <li>{@code CraftInventorySaddledMount} 及其子类把鞍与护甲排在主仓前面, 两者还各自是独立的 NMS 容器;
     *   <li>{@code CraftInventoryPlayer} 的装备槽在 NMS 背包里另有一套映射, 被单独特例处理.
     * </ul>
     */
    static final Set<Class<?>> SLOT_ALIGNED_INVENTORIES = BukkitProxy.findClasses(
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

    private final Inventory bukkitInventory; // 被引用的 Bukkit 容器, 只用来给出判等身份
    private final int size;                  // 被引用区段的槽位数量, 构造时取样
    private final int maxStackSize;          // 容器的堆叠上限, 构造时缓存

    /**
     * 记下判等身份与两个构造期取样的常量.
     *
     * @param bukkitInventory 被引用的 Bukkit 容器
     * @param size 被引用区段的槽位数量
     */
    private ContainerStorage(@NotNull Inventory bukkitInventory, int size) {
        this.bukkitInventory = bukkitInventory;
        this.size = size;
        this.maxStackSize = bukkitInventory.getMaxStackSize();
    }

    /**
     * 返回这一刻该读写的 NMS 容器.
     *
     * @return NMS 容器
     */
    @NotNull
    abstract Object container();

    @Override
    public int size() {
        return this.size;
    }

    @Override
    @Nullable
    public ItemStack read(int slot) {
        // NMS 容器用空物品表示空槽, 这里换成外部存储约定的 null
        Object handle = ContainerProxy.INSTANCE.getItem(this.container(), slot);
        return ItemUtils.nullIfEmpty(CraftItemStackProxy.INSTANCE.asCraftMirror(handle));
    }

    @Override
    public void write(int slot, @Nullable ItemStack item) {
        // 传入实例的所有权归存储, 取出它的句柄直接放进容器, 不必再复制一份
        Object handle = item == null ? ItemStackProxy.EMPTY : ItemUtils.getItemStackHandle(item);
        ContainerProxy.INSTANCE.setItem(this.container(), slot, handle);
    }

    @Override
    public int maxStackSize(int slot) {
        return this.maxStackSize;
    }

    @Override
    @NotNull
    public Object identity() {
        // 判等身份交给 Bukkit 容器, 同一个容器的两个 CraftInventory 包装仍然判定为同一处存储.
        return this.bukkitInventory;
    }

    /**
     * 容器在构造时就定下来的存储, 用于方块容器、实体容器和自建容器.
     */
    static final class Fixed extends ContainerStorage {
        private final Object container; // 构造时解出来的 NMS 容器

        Fixed(@NotNull Inventory bukkitInventory, @NotNull Object container, int size) {
            super(bukkitInventory, size);
            this.container = container;
        }

        @Override
        @NotNull
        Object container() {
            return this.container;
        }
    }

    /**
     * 玩家背包的存储, 每次访问都重新解析当前的 NMS 背包.
     * <p>只覆盖存储区段(主背包与快捷栏). 装备槽在 NMS 背包里走另一套槽位映射, 不能按同一组槽号读写,
     * 因此带装备槽的区段由 {@link BukkitStorage#of} 挡在 Bukkit 通道上.
     */
    static final class OfPlayer extends ContainerStorage {
        private final HumanEntity owner; // 背包主人, 跨死亡重生稳定

        OfPlayer(@NotNull Inventory bukkitInventory, @NotNull HumanEntity owner, int size) {
            super(bukkitInventory, size);
            this.owner = owner;
        }

        @Override
        @NotNull
        Object container() {
            // getInventory 读的是玩家实体上那个字段, 重生换过背包之后它给出的就是新的那一个
            return CraftInventoryProxy.INSTANCE.getInventory(this.owner.getInventory());
        }
    }
}
