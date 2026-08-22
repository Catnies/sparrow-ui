package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.CompoundContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.EntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MerchantContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity.BlockEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity.LecternInventoryProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

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

    private ContainerStorage(int size, int maxStackSize) {
        this.size = size;
        this.maxStackSize = maxStackSize;
    }

    // 这一刻该读写的 NMS 容器, 每次访问都问一遍, 因为玩家背包那种会被换掉.
    @NotNull
    abstract Object container();

    @Override
    public int size() {
        return this.size;
    }

    @Override
    @Nullable
    public ItemStack read(int slot) {
        Object handle = ContainerProxy.INSTANCE.getItem(this.container(), slot);
        // NMS 容器用空物品表示空槽, 这里换成外部存储约定的 null
        if (handle == ItemStackProxy.EMPTY) return null;
        return ItemUtils.nullIfEmpty(CraftItemStackProxy.INSTANCE.asCraftMirror(handle));
    }

    @Override
    @Nullable
    public ItemStack @NotNull [] readAll() {
        Object container = this.container();
        @Nullable ItemStack[] contents = new ItemStack[this.size];
        for (int slot = 0; slot < contents.length; slot++) {
            Object handle = ContainerProxy.INSTANCE.getItem(container, slot);
            // NMS 容器用空物品表示空槽, 这里换成外部存储约定的 null
            contents[slot] = handle != ItemStackProxy.EMPTY
                    ? ItemUtils.nullIfEmpty(CraftItemStackProxy.INSTANCE.asCraftMirror(handle))
                    : null;
        }
        return contents;
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

    // 找到真正存放这一格的那个容器, 顺手把槽号换算到它自己的坐标里.
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

    // 容器在构造时就定死的存储, 给方块容器, 实体容器和自建容器用.
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

        // 顺着容器问回它所属的方块实体或实体, 看那个宿主还在不在, 问不出宿主的一律当作还可用.
        private static boolean alive(Object container) {
            if (BlockEntityProxy.CLASS.isInstance(container)) {
                return !BlockEntityProxy.INSTANCE.isRemoved(container);
            }
            if (EntityProxy.CLASS.isInstance(container)) {
                return !EntityProxy.INSTANCE.isRemoved(container);
            }
            // 大箱子是两个容器拼起来的, 任何一半没了都算没了
            if (CompoundContainerProxy.CLASS.isInstance(container)) {
                return alive(CompoundContainerProxy.INSTANCE.getContainer1(container))
                        && alive(CompoundContainerProxy.INSTANCE.getContainer2(container));
            }
            // 讲台的容器是 LecternBlockEntity 的内部类, 它自己不是方块实体, 得检查讲台
            if (LecternInventoryProxy.CLASS.isInstance(container)) {
                return alive(LecternInventoryProxy.INSTANCE.getLectern(container));
            }
            // 交易容器同理, 它记着自己属于哪个商人, 而村民与流浪商人本身就是实体
            if (MerchantContainerProxy.CLASS.isInstance(container)) {
                return alive(MerchantContainerProxy.INSTANCE.getMerchant(container));
            }
            return true;
        }
    }

    // 玩家背包的存储, 每次访问都重新解析当前的 NMS 背包. 只覆盖存储区段(主背包与快捷栏) ——
    // 装备槽在 NMS 背包里走另一套槽位映射, 按同一组槽号读写会错位, 所以带装备槽的区段被 BukkitStorage.of 挡在 Bukkit 通道上.
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
            return !(this.owner instanceof Player player) || player.isConnected();
        }

        // getInventory 读的是玩家实体上那个字段, 重生换过背包之后它给出的就是新的那一个.
        @NotNull
        private static Object containerOf(@NotNull HumanEntity owner) {
            return CraftInventoryProxy.INSTANCE.getInventory(owner.getInventory());
        }
    }
}
