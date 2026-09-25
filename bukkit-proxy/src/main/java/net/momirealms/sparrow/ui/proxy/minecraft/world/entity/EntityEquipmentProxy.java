package net.momirealms.sparrow.ui.proxy.minecraft.world.entity;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.entity.EntityEquipment", activeIf = "min_version=26.2")
public interface EntityEquipmentProxy {
    EntityEquipmentProxy INSTANCE = ASMProxyFactory.create(EntityEquipmentProxy.class);

    @MethodInvoker(name = "get", activeIf = "min_version=26.2")
    Object get(Object target, @Type(name = "net.minecraft.world.entity.EquipmentSlot") Object slot);

    @MethodInvoker(name = "set", activeIf = "min_version=26.2")
    Object set(Object target, @Type(name = "net.minecraft.world.entity.EquipmentSlot") Object slot, @Type(name = "net.minecraft.world.item.ItemStack") Object item);
}
