package net.momirealms.sparrow.ui.proxy.minecraft.core.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.core.component.DataComponentHolder", activeIf = "min_version=1.20.5")
public interface DataComponentHolderProxy {
    DataComponentHolderProxy INSTANCE = ASMProxyFactory.create(DataComponentHolderProxy.class);

    @MethodInvoker(name = "get", activeIf = "min_version=1.20.5")
    Object component(Object target, @Type(name = "net.minecraft.core.component.DataComponentType") Object component);
}
