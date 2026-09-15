package net.momirealms.sparrow.ui.proxy.minecraft.core;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.core.NonNullList")
public interface NonNullListProxy {
    NonNullListProxy INSTANCE = ASMProxyFactory.create(NonNullListProxy.class);

    @MethodInvoker(name = "createWithCapacity", isStatic = true, activeIf = "min_version=1.21.4 && max_version=1.21.4")
    List<Object> createWithCapacity(int capacity);
}
