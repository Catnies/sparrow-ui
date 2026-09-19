package net.momirealms.sparrow.ui.proxy.minecraft.network;

import io.netty.channel.ChannelHandlerContext;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;

@ReflectionProxy(name = "net.minecraft.network.ProtocolSwapHandler", activeIf = "min_version=1.20.5")
public interface ProtocolSwapHandlerProxy {
    ProtocolSwapHandlerProxy INSTANCE = ASMProxyFactory.create(ProtocolSwapHandlerProxy.class);

    @MethodInvoker(name = "handleInboundTerminalPacket", isStatic = true)
    void handleInboundTerminalPacket(ChannelHandlerContext context, @Type(clazz = PacketProxy.class) Object packet);
}
