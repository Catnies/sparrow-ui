package net.momirealms.sparrow.ui.proxy.minecraft.world;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;

@ReflectionProxy(name = "net.minecraft.world.Container")
public interface ContainerProxy {
    ContainerProxy INSTANCE = ASMProxyFactory.create(ContainerProxy.class);

    @MethodInvoker(name = "getContainerSize")
    int getContainerSize(Object target);

    @MethodInvoker(name = "getItem")
    Object getItem(Object target, int slot);

    @MethodInvoker(name = "setItem")
    void setItem(Object target, int slot, @Type(clazz = ItemStackProxy.class) Object item);
}
