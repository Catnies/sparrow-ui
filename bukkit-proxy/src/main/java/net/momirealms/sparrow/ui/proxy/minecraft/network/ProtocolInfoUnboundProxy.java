package net.momirealms.sparrow.ui.proxy.minecraft.network;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.network.ProtocolInfo$Unbound", activeIf = "min_version=1.20.5 && max_version=1.21.4")
public interface ProtocolInfoUnboundProxy {
    ProtocolInfoUnboundProxy INSTANCE = ASMProxyFactory.create(ProtocolInfoUnboundProxy.class);
    Class<?> PACKET_VISITOR_CLASS = SparrowClass.find("net.minecraft.network.ProtocolInfo$Unbound$PacketVisitor");

    @MethodInvoker(name = "listPackets", activeIf = "min_version=1.20.5 && max_version=1.21.4")
    void listPackets(Object target, @Type(name = "net.minecraft.network.ProtocolInfo$Unbound$PacketVisitor") Object visitor);
}
