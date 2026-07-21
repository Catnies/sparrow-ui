package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * 创建 Paper 远端槽位镜像的容器同步器代理.
 */
@ReflectionProxy(name = "net.minecraft.world.inventory.ContainerSynchronizer")
public interface ContainerSynchronizerProxy {
    ContainerSynchronizerProxy INSTANCE = ASMProxyFactory.create(ContainerSynchronizerProxy.class);

    @MethodInvoker(name = "createSlot")
    Object createSlot(Object target);
}
