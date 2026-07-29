package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.inventory.ContainerSynchronizer")
public interface ContainerSynchronizerProxy {
    ContainerSynchronizerProxy INSTANCE = ASMProxyFactory.create(ContainerSynchronizerProxy.class);

    @MethodInvoker(name = "createSlot")
    Object createSlot(Object target);
}
