package net.momirealms.sparrow.ui.proxy.minecraft.world;

import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.Container")
public interface ContainerProxy {
    ContainerProxy INSTANCE = ASMProxyFactory.create(ContainerProxy.class);

    @MethodInvoker(name = "getContainerSize")
    int getContainerSize(Object target);

    @MethodInvoker(name = "setItem")
    void setItem(Object target, int slot, @Type(clazz = ItemStackProxy.class) Object item);

    @MethodInvoker(name = "setChanged")
    void setChanged(Object target);
}
