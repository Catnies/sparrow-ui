package net.momirealms.sparrow.ui.proxy.minecraft.network;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.network.ProtocolInfo$Details", activeIf = "min_version=1.21.5")
public interface ProtocolInfoDetailsProxy {
    ProtocolInfoDetailsProxy INSTANCE = ASMProxyFactory.create(ProtocolInfoDetailsProxy.class);
    Class<?> PACKET_VISITOR_CLASS = SparrowClass.find("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");

    @MethodInvoker(name = "listPackets", activeIf = "min_version=1.21.5")
    void listPackets(Object target, @Type(name = "net.minecraft.network.ProtocolInfo$Details$PacketVisitor") Object visitor);
}
