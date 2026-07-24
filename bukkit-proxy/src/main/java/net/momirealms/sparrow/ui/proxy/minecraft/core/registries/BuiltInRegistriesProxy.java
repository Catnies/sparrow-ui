package net.momirealms.sparrow.ui.proxy.minecraft.core.registries;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.registries.BuiltInRegistries")
public interface BuiltInRegistriesProxy {
    BuiltInRegistriesProxy INSTANCE = ASMProxyFactory.create(BuiltInRegistriesProxy.class);
    Object ITEM = INSTANCE.ITEM();

    @FieldGetter(name = "ITEM", isStatic = true)
    Object ITEM();
}
