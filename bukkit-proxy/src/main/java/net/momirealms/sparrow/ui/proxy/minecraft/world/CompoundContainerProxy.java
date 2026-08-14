package net.momirealms.sparrow.ui.proxy.minecraft.world;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.CompoundContainer")
public interface CompoundContainerProxy {
    CompoundContainerProxy INSTANCE = ASMProxyFactory.create(CompoundContainerProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.world.CompoundContainer");

    @FieldGetter(name = "container1")
    Object getContainer1(Object target);

    @FieldGetter(name = "container2")
    Object getContainer2(Object target);
}
