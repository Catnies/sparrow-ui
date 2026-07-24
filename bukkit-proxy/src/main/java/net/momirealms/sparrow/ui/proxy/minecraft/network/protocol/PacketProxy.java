package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol;

import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.Packet")
public interface PacketProxy {
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.Packet");
}
