package net.momirealms.sparrow.ui.proxy.minecraft.world.level.block.entity;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.level.block.entity.LecternBlockEntity$LecternInventory")
public interface LecternInventoryProxy {
    LecternInventoryProxy INSTANCE = ASMProxyFactory.create(LecternInventoryProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.world.level.block.entity.LecternBlockEntity$LecternInventory");

    @MethodInvoker(name = "getLectern")
    Object getLectern(Object target);
}
