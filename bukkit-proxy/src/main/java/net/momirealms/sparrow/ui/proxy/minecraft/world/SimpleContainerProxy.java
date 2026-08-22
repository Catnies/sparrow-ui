package net.momirealms.sparrow.ui.proxy.minecraft.world;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.SimpleContainer")
public interface SimpleContainerProxy extends ContainerProxy {
    Class<?> CLASS = SparrowClass.find("net.minecraft.world.SimpleContainer");
    SimpleContainerProxy INSTANCE = ASMProxyFactory.create(SimpleContainerProxy.class);

    @ConstructorInvoker
    Object newInstance(int size);

    @MethodInvoker(name = "getOwner")
    Object getOwner(Object target);
}
