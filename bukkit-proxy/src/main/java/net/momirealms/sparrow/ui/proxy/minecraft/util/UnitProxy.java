package net.momirealms.sparrow.ui.proxy.minecraft.util;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.util.Unit")
public interface UnitProxy {
    UnitProxy INSTANCE = ASMProxyFactory.create(UnitProxy.class);

    @FieldGetter(name = "INSTANCE", isStatic = true, activeIf = "min_version=1.20.5")
    Object getInstance();
}
