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

// 容器在构造时就定死的存储, 给方块容器, 实体容器和自建容器用.
// 世界里问得出宿主的容器一律弱持有, 让世界自己决定它什么时候消失; 问不出宿主的只有我们持有, 由本类钉住.
class FixedContainerStorage extends ContainerStorage {
    private final WeakReference<Object> containerRef; // 构造时解出来的 NMS 容器
    private final Object identity;                    // SlotKey 的判等归属, 构造时定死. 问不出宿主的容器归属就是它自己, 这份引用同时拦着它被回收

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

    // 顺着容器问回它在世界里的宿主, 问不出的返回 null.
    // 问得出宿主就等于世界替我们持有着这个容器, 问不出的只有我们自己持有.
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
            // 马和村民那种自带背包的实体, 背包记着自己属于谁
            Object owner = SimpleContainerProxy.INSTANCE.getOwner(container);
            return owner instanceof Entity ? owner : null;
        }
        return null;
    }

    // 判等归属. 如果查得出世界里的宿主就换成不牵住任何对象的值身份, 问不出的只好用容器自己.
    private static Object identityOf(Object container) {
        Object host = hostOf(container);
        if (host == null) {
            return container;
        }
        if (BlockEntityProxy.CLASS.isInstance(host)) {
            Object level = BlockEntityProxy.INSTANCE.getLevel(host);
            // 还没放进世界的方块实体没有稳定位置, 也就没有值身份可用
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
