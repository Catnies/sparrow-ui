package net.momirealms.sparrow.ui.proxy.minecraft.core;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.BlockPos")
public interface BlockPosProxy {
    BlockPosProxy INSTANCE = ASMProxyFactory.create(BlockPosProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.core.BlockPos");

    @MethodInvoker(name = "asLong")
    long asLong(Object target);
}
