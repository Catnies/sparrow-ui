package net.momirealms.sparrow.ui.proxy.minecraft.world;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.Container")
public interface ContainerProxy {
    ContainerProxy INSTANCE = ASMProxyFactory.create(ContainerProxy.class);
}