package net.momirealms.sparrow.ui.proxy.minecraft.world;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.SimpleContainer")
public interface SimpleContainerProxy extends ContainerProxy {
    SimpleContainerProxy INSTANCE = ASMProxyFactory.create(SimpleContainerProxy.class);

    @ConstructorInvoker
    Object newInstance(int size);
}
