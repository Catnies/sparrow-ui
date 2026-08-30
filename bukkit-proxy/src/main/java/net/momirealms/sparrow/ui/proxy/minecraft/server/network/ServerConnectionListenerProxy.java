package net.momirealms.sparrow.ui.proxy.minecraft.server.network;

import io.netty.channel.ChannelFuture;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.server.network.ServerConnectionListener", activeIf = "min_version=1.20.1")
public interface ServerConnectionListenerProxy {
    ServerConnectionListenerProxy INSTANCE = ASMProxyFactory.create(ServerConnectionListenerProxy.class);

    @FieldGetter(name = "channels", activeIf = "min_version=1.20.1")
    List<ChannelFuture> channels(Object target);

    @FieldSetter(name = "channels", activeIf = "min_version=1.20.1")
    void channels(Object target, List<ChannelFuture> channels);

    @MethodInvoker(name = "getConnections", activeIf = "min_version=1.20.1")
    List<?> connections(Object target);
}
