package net.momirealms.sparrow.ui.proxy.minecraft.core;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.stream.Stream;

@ReflectionProxy(name = "net.minecraft.core.Registry")
public interface RegistryProxy {
    RegistryProxy INSTANCE = ASMProxyFactory.create(RegistryProxy.class);

    @MethodInvoker(name = "stream", activeIf = "min_version=1.20.1")
    Stream<?> stream(Object target);

    @MethodInvoker(name = "getId", activeIf = "min_version=1.20.1")
    int getId(Object target, Object value);
}
