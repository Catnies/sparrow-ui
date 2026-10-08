package net.momirealms.sparrow.ui.proxy.minecraft.world.entity;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.entity.Mob")
public interface MobProxy {
    MobProxy INSTANCE = ASMProxyFactory.create(MobProxy.class);

    @MethodInvoker(name = "createEquipmentSlotContainer", activeIf = "min_version=1.21.5")
    Object createEquipmentSlotContainer(Object target, @Type(name = "net.minecraft.world.entity.EquipmentSlot") Object slot);
}
