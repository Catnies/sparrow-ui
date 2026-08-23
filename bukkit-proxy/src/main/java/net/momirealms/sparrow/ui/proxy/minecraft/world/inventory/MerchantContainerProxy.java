package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.inventory.MerchantContainer")
public interface MerchantContainerProxy {
    MerchantContainerProxy INSTANCE = ASMProxyFactory.create(MerchantContainerProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.world.inventory.MerchantContainer");

    @FieldGetter(name = "merchant", activeIf = "min_version=1.20.1")
    Object getMerchant(Object target);
}
