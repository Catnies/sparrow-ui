package net.momirealms.sparrow.ui.proxy.minecraft.world;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;

@ReflectionProxy(name = "net.minecraft.world.Container")
public interface ContainerProxy {
    ContainerProxy INSTANCE = ASMProxyFactory.create(ContainerProxy.class);

    @MethodInvoker(name = "getContainerSize", activeIf = "min_version=1.20.1")
    int getContainerSize(Object target);

    @MethodInvoker(name = "getMaxStackSize", activeIf = "min_version=1.20.1")
    int getMaxStackSize(Object target);

    @MethodInvoker(name = "getItem", activeIf = "min_version=1.20.1")
    Object getItem(Object target, int slot);

    @MethodInvoker(name = "setItem", activeIf = "min_version=1.20.1")
    void setItem(Object target, int slot, @Type(clazz = ItemStackProxy.class) Object item);
}
