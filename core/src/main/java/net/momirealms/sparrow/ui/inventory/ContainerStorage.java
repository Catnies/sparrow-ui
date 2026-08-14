package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.CompoundContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.EntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity.BlockEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * NMS 容器的外部存储适配: 槽位数量、堆叠上限与读写全部取自 NMS 容器自己.
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
    // 存储区段与 NMS 背包槽号一一对应的玩家背包实现.
    static final Set<Class<?>> SLOT_ALIGNED_PLAYER_INVENTORIES = BukkitProxy.findClasses(
            "org.bukkit.craftbukkit.inventory.CraftInventoryPlayer"
    );

    private final int size;         // 被引用区段的槽位数量, 构造时取样
    private final int maxStackSize; // 容器的堆叠上限, 构造时缓存

    /**
     * 记下两个构造期取样的常量.
     *
     * @param size 被引用区段的槽位数量
     * @param maxStackSize 容器的堆叠上限
     */
    private ContainerStorage(int size, int maxStackSize) {
        this.size = size;
        this.maxStackSize = maxStackSize;
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
    public SlotKey keyOf(int slot) {
        return keyOf(this.container(), slot);
    }

    /**
     * 定位真正存放这一格的那个容器, 并把槽号换算到它自己的坐标里.
     * <p>
     *
     * @param container NMS 容器
     * @param slot 该容器内的槽位
     * @return 该槽位的 SlotKey
     */
    @NotNull
    private static SlotKey keyOf(Object container, int slot) {
        // CompoundContainer (比如大箱子) 是两个 Container 接起来的, 归属需要具体到被包装的 Container
        if (CompoundContainerProxy.CLASS.isInstance(container)) {
            Object first = CompoundContainerProxy.INSTANCE.getContainer1(container);
            int firstSize = ContainerProxy.INSTANCE.getContainerSize(first);
            if (slot < firstSize) {
                return keyOf(first, slot);
            }
            return keyOf(CompoundContainerProxy.INSTANCE.getContainer2(container), slot - firstSize);
        }
        // 其余的正常引用 container 本身
        return new SlotKey(container, slot);
    }

    /**
     * 容器在构造时就定下来的存储, 用于方块容器、实体容器和自建容器.
     */
    static final class Fixed extends ContainerStorage {
        private final Object container; // 构造时解出来的 NMS 容器

        Fixed(@NotNull Object container) {
            super(ContainerProxy.INSTANCE.getContainerSize(container), ContainerProxy.INSTANCE.getMaxStackSize(container));
            this.container = container;
        }

        @Override
        @NotNull
        Object container() {
            return this.container;
        }

        @Override
        public boolean alive() {
            return alive(this.container);
        }

        /**
         * 判断一个 NMS 容器背后的东西是不是还在.
         * <p>方块容器与矿车、船这类实体容器, NMS 那边的容器对象本身就是方块实体或实体, 直接问它自己
         * 被移除了没有. 大箱子是两个容器拼起来的, 任何一半没了都算没了.
         *
         * @param container NMS 容器
         * @return 还在时返回 true
         */
        private static boolean alive(Object container) {
            if (BlockEntityProxy.CLASS.isInstance(container)) {
                return !BlockEntityProxy.INSTANCE.isRemoved(container);
            }
            if (EntityProxy.CLASS.isInstance(container)) {
                return !EntityProxy.INSTANCE.isRemoved(container);
            }
            if (CompoundContainerProxy.CLASS.isInstance(container)) {
                return alive(CompoundContainerProxy.INSTANCE.getContainer1(container))
                        && alive(CompoundContainerProxy.INSTANCE.getContainer2(container));
            }
            return true;
        }
    }

    /**
     * 玩家背包的存储, 每次访问都重新解析当前的 NMS 背包.
     * <p>只覆盖存储区段(主背包与快捷栏). 装备槽在 NMS 背包里走另一套槽位映射, 不能按同一组槽号读写,
     * 因此带装备槽的区段由 {@link BukkitStorage#of} 挡在 Bukkit 通道上.
     */
    static final class OfPlayer extends ContainerStorage {
        private final HumanEntity owner; // 背包主人, 跨死亡重生稳定

        OfPlayer(@NotNull HumanEntity owner, int size) {
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
            // 归属跟着玩家走: 重生换掉的是背包, 那条 NMS 背包拿来当归属重生前后就不判等了
            return new SlotKey(this.owner.getUniqueId(), slot);
        }

        @Override
        public boolean alive() {
            // 玩家退出后那个背包就与服务端脱钩了: 写进去的不再存盘, 从里面取出的却还在存档里,
            // 前者丢件后者刷件. 死亡重生不算脱钩, 所以这里问的是在不在线, 不是活没活着.
            return !(this.owner instanceof Player player) || player.isOnline();
        }

        // getInventory 读的是玩家实体上那个字段, 重生换过背包之后它给出的就是新的那一个.
        @NotNull
        private static Object containerOf(@NotNull HumanEntity owner) {
            return CraftInventoryProxy.INSTANCE.getInventory(owner.getInventory());
        }
    }
}
