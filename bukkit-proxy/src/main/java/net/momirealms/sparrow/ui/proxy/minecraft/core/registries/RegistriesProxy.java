package net.momirealms.sparrow.ui.proxy.minecraft.core.registries;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.registries.Registries")
public interface RegistriesProxy {
    RegistriesProxy INSTANCE = ASMProxyFactory.create(RegistriesProxy.class);
    Object RECIPE = INSTANCE.RECIPE();
    Object ENCHANTMENT = INSTANCE.ENCHANTMENT();

    @FieldGetter(name = "RECIPE", isStatic = true)
    Object RECIPE();

    @FieldGetter(name = "ENCHANTMENT", isStatic = true)
    Object ENCHANTMENT();
}
