package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.Packet")
public interface PacketProxy {
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.Packet");
}
