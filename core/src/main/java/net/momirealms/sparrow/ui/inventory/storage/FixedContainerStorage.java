package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.minecraft.core.BlockPosProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.SimpleContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.EntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MerchantContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.LevelProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity.BlockEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity.LecternInventoryProxy;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;

// 世界宿主持有的容器使用弱引用, 无法定位宿主时, 容器本身同时充当稳定身份.
class FixedContainerStorage extends ContainerStorage {
    private final WeakReference<Object> containerRef;
    private final Object identity; // 无宿主容器由此强引用维持生命周期

    FixedContainerStorage(@NotNull Object container) {
        super(ContainerProxy.INSTANCE.getContainerSize(container), ContainerProxy.INSTANCE.getMaxStackSize(container));
        this.containerRef = new WeakReference<>(container);
        this.identity = identityOf(container);
    }

    @Override
    @Nullable
    Object container() {
        return this.containerRef.get();
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        return new SlotKey(this.identity, slot);
    }

    @Override
    public boolean alive() {
        Object container = this.container();
        return container != null && alive(hostOf(container));
    }

    // 宿主还在不在, 问不出宿主的一律当作还可用.
    private static boolean alive(@Nullable Object host) {
        if (host == null) {
            return true;
        }
        if (BlockEntityProxy.CLASS.isInstance(host)) {
            return !BlockEntityProxy.INSTANCE.isRemoved(host);
        }
        if (EntityProxy.CLASS.isInstance(host)) {
            return !EntityProxy.INSTANCE.isRemoved(host);
        }
        return ((Entity) host).isValid();
    }

    // 定位负责持有容器的世界对象, 无宿主容器返回 null.
    @Nullable
    private static Object hostOf(Object container) {
        if (BlockEntityProxy.CLASS.isInstance(container) || EntityProxy.CLASS.isInstance(container)) {
            return container;
        }
        if (LecternInventoryProxy.CLASS.isInstance(container)) {
            return hostOf(LecternInventoryProxy.INSTANCE.getLectern(container));
        }
        if (MerchantContainerProxy.CLASS.isInstance(container)) {
            return hostOf(MerchantContainerProxy.INSTANCE.getMerchant(container));
        }
        if (SimpleContainerProxy.CLASS.isInstance(container)) {
            Object owner = SimpleContainerProxy.INSTANCE.getOwner(container);
            return owner instanceof Entity ? owner : null;
        }
        return null;
    }

    // 世界对象换成值身份, 避免 SlotKey 延长实体或区块生命周期.
    private static Object identityOf(Object container) {
        Object host = hostOf(container);
        if (host == null) {
            return container;
        }
        if (BlockEntityProxy.CLASS.isInstance(host)) {
            Object level = BlockEntityProxy.INSTANCE.getLevel(host);
            // 尚未放入世界的方块实体没有稳定位置.
            if (level == null) {
                return container;
            }
            World world = (World) LevelProxy.INSTANCE.getWorld(level);
            return new BlockAnchor(world.getUID(), BlockPosProxy.INSTANCE.asLong(BlockEntityProxy.INSTANCE.getBlockPos(host)));
        }
        if (EntityProxy.CLASS.isInstance(host)) {
            return EntityProxy.INSTANCE.getUUID(host);
        }
        return ((Entity) host).getUniqueId();
    }
}
