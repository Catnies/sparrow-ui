package net.momirealms.sparrow.ui.proxy.minecraft.server.network;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * 从服务端玩家包监听器取得 Minecraft Connection 的代理.
 */
@ReflectionProxy(name = "net.minecraft.server.network.ServerCommonPacketListenerImpl")
public interface ServerCommonPacketListenerImplProxy {
    ServerCommonPacketListenerImplProxy INSTANCE = ASMProxyFactory.create(ServerCommonPacketListenerImplProxy.class);

    @FieldGetter(name = "connection")
    Object connection(Object target);
}
