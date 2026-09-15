package net.momirealms.sparrow.ui.proxy.minecraft.core.registries;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.registries.Registries")
public interface RegistriesProxy {
    RegistriesProxy INSTANCE = ASMProxyFactory.create(RegistriesProxy.class);
    Object RECIPE = INSTANCE.RECIPE();
    Object ENCHANTMENT = INSTANCE.ENCHANTMENT();
    Object MAP_DECORATION_TYPE = INSTANCE.getMapDecorationType();

    @FieldGetter(name = "RECIPE", isStatic = true, activeIf = "min_version=1.21")
    Object RECIPE();

    @FieldGetter(name = "ENCHANTMENT", isStatic = true, activeIf = "min_version=1.20.1")
    Object ENCHANTMENT();

    @FieldGetter(name = "MAP_DECORATION_TYPE", isStatic = true, activeIf = "min_version=1.20.5")
    Object getMapDecorationType();
}
