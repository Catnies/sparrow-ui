package net.momirealms.sparrow.ui.proxy.minecraft.world.entity;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.UUID;

@ReflectionProxy(name = "net.minecraft.world.entity.Entity")
public interface EntityProxy {
    EntityProxy INSTANCE = ASMProxyFactory.create(EntityProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.world.entity.Entity");

    @MethodInvoker(name = "isRemoved", activeIf = "min_version=1.20.1")
    boolean isRemoved(Object target);

    @MethodInvoker(name = "getUUID", activeIf = "min_version=1.20.1")
    UUID getUUID(Object target);
}
