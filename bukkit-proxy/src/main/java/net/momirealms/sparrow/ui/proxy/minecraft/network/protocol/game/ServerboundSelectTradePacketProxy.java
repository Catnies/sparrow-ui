package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundSelectTradePacket")
public interface ServerboundSelectTradePacketProxy extends PacketProxy {
    ServerboundSelectTradePacketProxy INSTANCE = ASMProxyFactory.create(ServerboundSelectTradePacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ServerboundSelectTradePacket");

    @MethodInvoker(name = "getItem")
    int getItem(Object target);
}
