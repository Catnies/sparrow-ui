package net.momirealms.sparrow.ui.proxy.minecraft.world.level;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.level.Level")
public interface LevelProxy {
    LevelProxy INSTANCE = ASMProxyFactory.create(LevelProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.world.level.Level");

    @MethodInvoker(name = "getWorld")
    Object getWorld(Object target);
}
