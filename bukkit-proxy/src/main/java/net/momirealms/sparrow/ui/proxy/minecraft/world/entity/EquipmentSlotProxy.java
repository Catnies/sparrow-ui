package net.momirealms.sparrow.ui.proxy.minecraft.world.entity;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.entity.EquipmentSlot")
public interface EquipmentSlotProxy {
    EquipmentSlotProxy INSTANCE = ASMProxyFactory.create(EquipmentSlotProxy.class);
    Object SADDLE = INSTANCE.saddle();
    Object BODY = INSTANCE.body();

    @FieldGetter(name = "SADDLE", isStatic = true, activeIf = "min_version=1.21.5")
    Object saddle();

    @FieldGetter(name = "BODY", isStatic = true, activeIf = "min_version=1.20.5")
    Object body();
}
