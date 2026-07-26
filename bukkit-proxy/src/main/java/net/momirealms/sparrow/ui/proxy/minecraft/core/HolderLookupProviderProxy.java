package net.momirealms.sparrow.ui.proxy.minecraft.core;

import com.mojang.serialization.DynamicOps;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.HolderLookup$Provider")
public interface HolderLookupProviderProxy {
    HolderLookupProviderProxy INSTANCE = ASMProxyFactory.create(HolderLookupProviderProxy.class);

    @MethodInvoker(name = "createSerializationContext")
    DynamicOps<Object> createSerializationContext(Object target, DynamicOps<Object> ops);
}
