package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.Packet")
public interface PacketProxy {
    PacketProxy INSTANCE = ASMProxyFactory.create(PacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.Packet");

    @MethodInvoker(name = "type", activeIf = "min_version=1.20.5")
    Object type(Object target);
}
