package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryCraftingProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryAbstractHorseProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryPlayerProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventorySaddledMountProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftResultInventoryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.SimpleContainerProxy;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

// 同一个容器, Bukkit 那层的槽号和底层 NMS Container 的槽号不一定对得上.
// 这里按容器类型记住两边怎么换算, 对得上就能直接走 NMS 通道读写, 对不上就只能退回 Bukkit 接口.
enum BukkitInventoryLayout {
    // Bukkit 与 NMS 槽号相同
    ALIGNED {
        @Override
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            return ContainerStorage.of(CraftInventoryProxy.INSTANCE.getInventory(inventory));
        }
    },
    // Spigot 的 Bukkit 内容只包含主仓, 槽位身份仍使用完整坐骑布局的偏移.
    MOUNT_STORAGE {
        @Override
        @Nullable
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            Object main = CraftInventoryProxy.INSTANCE.getInventory(inventory);
            UUID mount = mountOf(main);
            return mount == null ? null : new MountContainerStorage(main, mount, MAIN_FIRST_SLOT);
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
    // 合成台这类, Bukkit 把结果格排在合成格前面, NMS 那边也是 0 号位放结果
    CRAFTING {
        @Override
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            return new SplicedStorage(
                    ContainerStorage.of(CraftInventoryCraftingProxy.INSTANCE.getResultInventory(inventory)),
                    ContainerStorage.of(CraftInventoryCraftingProxy.INSTANCE.getMatrixInventory(inventory))
            );
        }
    },
    // 按 Bukkit 槽号排列坐骑装备与储物格
    SADDLED_MOUNT {
        @Override
        @Nullable
        ExternalStorage build(@NotNull Inventory inventory, int size) {
            Object main = CraftInventorySaddledMountProxy.INSTANCE.getMainInventory(inventory);
            // 坐骑 UUID 与 Bukkit 槽号共同标识物理位置.
            UUID mount = mountOf(main);
            if (mount == null) {
                return null;
            }
            if (!VersionHelper.isOrAbove1_21_5) {
                return new HorseContainerStorage(main, CraftInventorySaddledMountProxy.INSTANCE.getArmorInventory(inventory), mount);
            }
            return new SplicedStorage(
                    new MountContainerStorage(CraftInventorySaddledMountProxy.INSTANCE.getSaddleInventory(inventory), mount, SADDLE_SLOT),
                    new MountContainerStorage(CraftInventorySaddledMountProxy.INSTANCE.getArmorInventory(inventory), mount, BODY_ARMOR_SLOT),
                    new MountContainerStorage(main, mount, MAIN_FIRST_SLOT)
            );
        }
    },
    // 认不出来的排布, 一律走 Bukkit 接口
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

    // 布局认得出、尺寸也对得上, 就给一份直通 NMS 的存储; 否则老实退回 Bukkit 通道.
    @Nullable
    static ExternalStorage storageOf(@NotNull Inventory inventory, int size) {
        ExternalStorage storage = LAYOUTS.get(inventory.getClass()).build(inventory, size);
        return storage != null && storage.size() == size ? storage : null;
    }

    @Nullable
    abstract ExternalStorage build(@NotNull Inventory inventory, int size);

    private static BukkitInventoryLayout layoutOf(Class<?> type) {
        // 玩家背包的槽位身份挂在玩家上, 而不是挂在容器实例上. 玩家重生之后容器换了, 槽位还得认得出是同一格.
        if (CraftInventoryPlayerProxy.CLASS != null && CraftInventoryPlayerProxy.CLASS.isAssignableFrom(type)) {
            return PLAYER;
        }
        Class<?> owner = slotCoordinateOwner(type);
        if (owner == null) {
            return FOREIGN;
        }
        // ResultInventory 只暴露结果格, 但它的内容区槽号和 getInventory() 那边仍然是对齐的.
        if (owner == CraftInventoryProxy.CLASS || owner == CraftResultInventoryProxy.CLASS) {
            if (!VersionHelper.hasPaperPatch && CraftInventoryAbstractHorseProxy.CLASS.isAssignableFrom(type)) {
                return MOUNT_STORAGE;
            }
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

    // <strong>读和写必须由同一层声明</strong>. 一边被子类改过槽号另一边没改, 两套坐标就对不上了, 写进去会落到别的格.
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

    // 主仓要记住自己属于哪只坐骑, 换一只坐骑就是另一批槽位.
    @Nullable
    private static UUID mountOf(Object mainContainer) {
        if (!SimpleContainerProxy.CLASS.isInstance(mainContainer)) {
            return null;
        }
        Object owner = SimpleContainerProxy.INSTANCE.getOwner(mainContainer);
        return owner instanceof Entity entity ? entity.getUniqueId() : null;
    }
}
