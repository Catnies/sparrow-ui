package net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.level.block.entity.BlockEntity")
public interface BlockEntityProxy {
    BlockEntityProxy INSTANCE = ASMProxyFactory.create(BlockEntityProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.world.level.block.entity.BlockEntity");

    @MethodInvoker(name = "isRemoved", activeIf = "min_version=1.20.1")
    boolean isRemoved(Object target);

    @MethodInvoker(name = "getLevel", activeIf = "min_version=1.20.1")
    Object getLevel(Object target);

    @MethodInvoker(name = "getBlockPos", activeIf = "min_version=1.20.1")
    Object getBlockPos(Object target);
}
