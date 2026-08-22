package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.SimpleContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.EntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MerchantContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity.BlockEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity.LecternInventoryProxy;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

// 容器在构造时就定死的存储, 给方块容器, 实体容器和自建容器用.
final class FixedContainerStorage extends ContainerStorage {
    private final Object container; // 构造时解出来的 NMS 容器

    FixedContainerStorage(@NotNull Object container) {
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
    private static boolean alive(Object anchor) {
        if (BlockEntityProxy.CLASS.isInstance(anchor)) {
            return !BlockEntityProxy.INSTANCE.isRemoved(anchor);
        }
        if (EntityProxy.CLASS.isInstance(anchor)) {
            return !EntityProxy.INSTANCE.isRemoved(anchor);
        }
        if (LecternInventoryProxy.CLASS.isInstance(anchor)) {
            return alive(LecternInventoryProxy.INSTANCE.getLectern(anchor));
        }
        if (MerchantContainerProxy.CLASS.isInstance(anchor)) {
            return alive(MerchantContainerProxy.INSTANCE.getMerchant(anchor));
        }
        if (SimpleContainerProxy.CLASS.isInstance(anchor)) {
            Object owner = SimpleContainerProxy.INSTANCE.getOwner(anchor);
            return !(owner instanceof Entity entity) || entity.isValid();
        }
        return true;
    }
}
