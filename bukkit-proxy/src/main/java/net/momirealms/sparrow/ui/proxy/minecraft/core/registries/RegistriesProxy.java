package net.momirealms.sparrow.ui.proxy.minecraft.core.registries;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.registries.Registries")
public interface RegistriesProxy {
    RegistriesProxy INSTANCE = ASMProxyFactory.create(RegistriesProxy.class);
    Object RECIPE = INSTANCE.RECIPE();

    @FieldGetter(name = "RECIPE", isStatic = true)
    Object RECIPE();
}
