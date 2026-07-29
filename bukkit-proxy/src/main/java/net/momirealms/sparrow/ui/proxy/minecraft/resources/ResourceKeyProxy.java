package net.momirealms.sparrow.ui.proxy.minecraft.resources;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.*;

@ReflectionProxy(name = "net.minecraft.resources.ResourceKey")
public interface ResourceKeyProxy {
    ResourceKeyProxy INSTANCE = ASMProxyFactory.create(ResourceKeyProxy.class);

    @FieldGetter(name = "registryName")
    Object getRegistryName(Object target);

    @FieldSetter(name = "registryName")
    void setRegistryName(Object target, Object registryName);

    @FieldGetter(name = {"identifier", "location"})
    Object getIdentifier(Object target);

    @FieldSetter(name = {"identifier", "location"})
    void setIdentifier(Object target, Object identifier);

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
