package net.momirealms.sparrow.ui.proxy.minecraft.network;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.ProtocolInfo$DetailsProvider", activeIf = "min_version=1.21.5")
public interface ProtocolInfoDetailsProviderProxy {
    ProtocolInfoDetailsProviderProxy INSTANCE = ASMProxyFactory.create(ProtocolInfoDetailsProviderProxy.class);

    @MethodInvoker(name = "details", activeIf = "min_version=1.21.5")
    Object details(Object target);
}
