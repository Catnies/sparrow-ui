package net.momirealms.sparrow.ui.proxy.minecraft.core.registries;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.registries.BuiltInRegistries")
public interface BuiltInRegistriesProxy {
    BuiltInRegistriesProxy INSTANCE = ASMProxyFactory.create(BuiltInRegistriesProxy.class);
    Object ITEM = INSTANCE.ITEM();

    @FieldGetter(name = "ITEM", isStatic = true)
    Object ITEM();
}
