package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryCraftingProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryPlayerProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventorySaddledMountProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftResultInventoryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.SimpleContainerProxy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

// 一个 Bukkit Inventory 的槽号与 NMS Container 槽号之间的关系, 也就决定了它能不能直接读写 NMS 容器.
enum BukkitInventoryLayout {
    // 槽号就是 getInventory() 那个容器的槽号
    ALIGNED {
        @Override
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            return ContainerStorage.of(CraftInventoryProxy.INSTANCE.getInventory(inventory));
        }
    },
    // 玩家背包, 坐标同 ALIGNED, 但归属跟着玩家走
    PLAYER {
        @Override
        @Nullable
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            HumanEntity owner = ((PlayerInventory) inventory).getHolder();
            return owner == null ? null : new PlayerContainerStorage(owner, size);
        }
    },
    // 结果格接着合成格, 而 getInventory() 只给出合成格
    CRAFTING {
        @Override
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            return new SplicedStorage(
                    ContainerStorage.of(CraftInventoryCraftingProxy.INSTANCE.getResultInventory(inventory)),
                    ContainerStorage.of(CraftInventoryCraftingProxy.INSTANCE.getMatrixInventory(inventory))
            );
        }
    },
    // 鞍接着护甲再接着主仓, 而 getInventory() 只给出主仓
    SADDLED_MOUNT {
        @Override
        @Nullable
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            Object main = CraftInventorySaddledMountProxy.INSTANCE.getMainInventory(inventory);
            // 鞍与护甲的容器每次取出来都是新的, 归属得从主仓问出那只坐骑
            UUID mount = mountOf(main);
            if (mount == null) {
                return null;
            }
            return new SplicedStorage(
                    new MountContainerStorage(CraftInventorySaddledMountProxy.INSTANCE.getSaddleInventory(inventory), mount, SADDLE_SLOT),
                    new MountContainerStorage(CraftInventorySaddledMountProxy.INSTANCE.getArmorInventory(inventory), mount, BODY_ARMOR_SLOT),
                    new MountContainerStorage(main, mount, MAIN_FIRST_SLOT)
            );
        }
    },
    // 哪种排布都不是
    FOREIGN {
        @Override
        @Nullable
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            return null;
        }
    };

    private static final int SADDLE_SLOT = 0;      // 坐骑背包的鞍位
    private static final int BODY_ARMOR_SLOT = 1;  // 坐骑背包的护甲位
    private static final int MAIN_FIRST_SLOT = 2;  // 坐骑背包主仓的第一格
    private static final ClassValue<BukkitInventoryLayout> LAYOUTS = new ClassValue<>() {
        @Override
        protected BukkitInventoryLayout computeValue(Class<?> type) {
            return layoutOf(type);
        }
    };

    // 交出这个 Bukkit 容器背后的 NMS 存储, 认不出排布时交出 null.
    @Nullable
    static ExternalStorage storageOf(@NotNull Inventory inventory, int size) {
        ExternalStorage storage = LAYOUTS.get(inventory.getClass()).build(inventory, size);
        // 段长和被引用区段对不上, 说明这个实现的排布另有讲究, 交回 Bukkit 通道
        return storage != null && storage.size() == size ? storage : null;
    }

    // 按这一层的排布把各段包出来.
    @Nullable
    abstract ExternalStorage build(@NotNull Inventory inventory, int size);

    // 认出这个实现类的槽位排布.
    private static BukkitInventoryLayout layoutOf(Class<?> type) {
        // 玩家背包的坐标和 ALIGNED 一样, 单独认出来是因为归属要跟着玩家走, 重生换了背包对象也得判等
        if (CraftInventoryPlayerProxy.CLASS != null && CraftInventoryPlayerProxy.CLASS.isAssignableFrom(type)) {
            return PLAYER;
        }
        Class<?> owner = slotCoordinateOwner(type);
        if (owner == null) {
            return FOREIGN;
        }
        // 结果格那一族的 getContents 只给出原料格, 而原料格就是 getInventory() 那个容器, 槽号照样对得上
        if (owner == CraftInventoryProxy.CLASS || owner == CraftResultInventoryProxy.CLASS) {
            return ALIGNED;
        }
        if (owner == CraftInventoryCraftingProxy.CLASS) {
            return CRAFTING;
        }
        if (owner == CraftInventorySaddledMountProxy.CLASS) {
            return SADDLED_MOUNT;
        }
        return FOREIGN;
    }

    // 重写过 getItem 或 setItem 的那一层自带一套坐标, 两个都没重写就还是 CraftInventory 那一套.
    @Nullable
    private static Class<?> slotCoordinateOwner(Class<?> type) {
        if (!CraftInventoryProxy.CLASS.isAssignableFrom(type)) return null;
        // getItem 与 setItem 的直接实现类如果是同一个实现, 那么就将这个实现类返回.
        try {
            Class<?> reader = type.getMethod("getItem", int.class).getDeclaringClass();
            Class<?> writer = type.getMethod("setItem", int.class, ItemStack.class).getDeclaringClass();
            // 两个方法分属两层意味着读和写各按各的坐标走, 可能是自定义实现.
            return reader == writer ? reader : null;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Bukkit inventory " + type.getName() + " has no slot accessors", exception);
        }
    }

    // 主仓记着自己属于哪只坐骑.
    @Nullable
    private static UUID mountOf(Object mainContainer) {
        if (!SimpleContainerProxy.CLASS.isInstance(mainContainer)) {
            return null;
        }
        Object owner = SimpleContainerProxy.INSTANCE.getOwner(mainContainer);
        return owner instanceof Entity entity ? entity.getUniqueId() : null;
    }
}
