package net.momirealms.sparrow.ui.proxy.minecraft.resources;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.resources.ResourceKey")
public interface ResourceKeyProxy {
    ResourceKeyProxy INSTANCE = ASMProxyFactory.create(ResourceKeyProxy.class);

    @MethodInvoker(name = "create", isStatic = true)
    Object create(
            @Type(name = "net.minecraft.resources.ResourceKey") Object registryKey,
            @Type(clazz = IdentifierProxy.class) Object identifier
    );

    @MethodInvoker(name = "location", activeIf = "!min_version=26.1")
    Object location(Object target);

    @MethodInvoker(name = "identifier", activeIf = "min_version=26.1")
    Object identifier(Object target);
}
