package net.momirealms.sparrow.ui.proxy.minecraft.core;

import com.mojang.serialization.DynamicOps;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.HolderLookup$Provider")
public interface HolderLookupProviderProxy {
    HolderLookupProviderProxy INSTANCE = ASMProxyFactory.create(HolderLookupProviderProxy.class);

    @MethodInvoker(name = "createSerializationContext", activeIf = "min_version=1.20.5")
    DynamicOps<Object> createSerializationContext(Object target, DynamicOps<Object> ops);
}
