package net.momirealms.sparrow.ui.proxy.minecraft.core.component;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.core.component.DataComponentExactPredicate")
public interface DataComponentExactPredicateProxy {
    DataComponentExactPredicateProxy INSTANCE = ASMProxyFactory.create(DataComponentExactPredicateProxy.class);

    @MethodInvoker(name = "allOf", isStatic = true)
    Object allOf(@Type(name = "net.minecraft.core.component.DataComponentMap") Object components);
}
