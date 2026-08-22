package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.minecraft.world.CompoundContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.EntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MerchantContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity.BlockEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity.LecternInventoryProxy;
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
