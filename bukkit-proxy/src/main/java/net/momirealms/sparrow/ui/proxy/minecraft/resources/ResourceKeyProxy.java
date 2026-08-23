package net.momirealms.sparrow.ui.proxy.minecraft.resources;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.*;

@ReflectionProxy(name = "net.minecraft.resources.ResourceKey")
public interface ResourceKeyProxy {
    ResourceKeyProxy INSTANCE = ASMProxyFactory.create(ResourceKeyProxy.class);

    @FieldGetter(name = "registryName", activeIf = "min_version=1.20.1")
    Object getRegistryName(Object target);

    @FieldSetter(name = "registryName", activeIf = "min_version=1.20.1")
    void setRegistryName(Object target, Object registryName);

    @FieldGetter(name = {"identifier", "location"}, activeIf = "min_version=1.20.1")
    Object getIdentifier(Object target);

    @FieldSetter(name = {"identifier", "location"}, activeIf = "min_version=1.20.1")
    void setIdentifier(Object target, Object identifier);

    @MethodInvoker(name = "create", isStatic = true, activeIf = "min_version=1.20.1")
    Object create(
            @Type(name = "net.minecraft.resources.ResourceKey") Object registryKey,
            @Type(clazz = IdentifierProxy.class) Object identifier
    );

    @MethodInvoker(name = "location", activeIf = "min_version=1.20.1 && max_version=1.21.10")
    Object location(Object target);

    @MethodInvoker(name = "identifier", activeIf = "min_version=1.21.11")
    Object identifier(Object target);
}
