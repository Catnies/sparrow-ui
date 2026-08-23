package net.momirealms.sparrow.ui.proxy.minecraft.server.network;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.network.ServerCommonPacketListenerImpl", activeIf = "min_version=1.20.2")
public interface ServerCommonPacketListenerImplProxy {
    ServerCommonPacketListenerImplProxy INSTANCE = ASMProxyFactory.create(ServerCommonPacketListenerImplProxy.class);

    @FieldGetter(name = "connection", activeIf = "min_version=1.20.2")
    Object connection(Object target);
}
