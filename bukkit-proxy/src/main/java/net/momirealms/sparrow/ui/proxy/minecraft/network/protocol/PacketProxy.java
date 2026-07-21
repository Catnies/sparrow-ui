package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol;

import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * Minecraft 数据包的稳定类型标记和运行时类型令牌.
 */
@ReflectionProxy(name = "net.minecraft.network.protocol.Packet")
public interface PacketProxy {
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.Packet");
}
