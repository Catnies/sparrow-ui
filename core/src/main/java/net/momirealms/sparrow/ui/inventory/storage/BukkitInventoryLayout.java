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

// Bukkit Inventory 与底层 NMS Container 的槽位布局.
enum BukkitInventoryLayout {
    // Bukkit 与 NMS 槽号相同
    ALIGNED {
        @Override
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            return ContainerStorage.of(CraftInventoryProxy.INSTANCE.getInventory(inventory));
        }
    },
    // 槽号相同, 槽位身份跟随玩家
    PLAYER {
        @Override
        @Nullable
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            HumanEntity owner = ((PlayerInventory) inventory).getHolder();
            return owner == null ? null : new PlayerContainerStorage(owner, size);
        }
    },
    // 结果格位于合成格之前
    CRAFTING {
        @Override
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            return new SplicedStorage(
                    ContainerStorage.of(CraftInventoryCraftingProxy.INSTANCE.getResultInventory(inventory)),
                    ContainerStorage.of(CraftInventoryCraftingProxy.INSTANCE.getMatrixInventory(inventory))
            );
        }
    },
    // 鞍, 护甲和主仓依次拼接
    SADDLED_MOUNT {
        @Override
        @Nullable
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            Object main = CraftInventorySaddledMountProxy.INSTANCE.getMainInventory(inventory);
            // 鞍与护甲容器每次读取都会重建, 槽位身份取自坐骑.
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
    // 未知排布
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

    // 返回布局匹配的 NMS 存储, 未知或尺寸不符时回退 Bukkit 通道.
    @Nullable
    static ExternalStorage storageOf(@NotNull Inventory inventory, int size) {
        ExternalStorage storage = LAYOUTS.get(inventory.getClass()).build(inventory, size);
        return storage != null && storage.size() == size ? storage : null;
    }

    @Nullable
    abstract ExternalStorage build(@NotNull Inventory inventory, int size);

    private static BukkitInventoryLayout layoutOf(Class<?> type) {
        // 玩家背包使用玩家身份, 重生后仍能判定为相同槽位.
        if (CraftInventoryPlayerProxy.CLASS != null && CraftInventoryPlayerProxy.CLASS.isAssignableFrom(type)) {
            return PLAYER;
        }
        Class<?> owner = slotCoordinateOwner(type);
        if (owner == null) {
            return FOREIGN;
        }
        // ResultInventory 的内容区仍与 getInventory() 的槽号对齐.
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

    // <strong>读写方法必须由同一层声明</strong>, 才能确认两边使用同一套槽号.
    @Nullable
    private static Class<?> slotCoordinateOwner(Class<?> type) {
        if (!CraftInventoryProxy.CLASS.isAssignableFrom(type)) return null;
        try {
            Class<?> reader = type.getMethod("getItem", int.class).getDeclaringClass();
            Class<?> writer = type.getMethod("setItem", int.class, ItemStack.class).getDeclaringClass();
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
