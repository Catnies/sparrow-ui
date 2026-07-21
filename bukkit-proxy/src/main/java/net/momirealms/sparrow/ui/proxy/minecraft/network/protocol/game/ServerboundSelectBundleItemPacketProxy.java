package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * 读取客户端 Bundle 物品选择的数据包代理.
 */
@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket")
public interface ServerboundSelectBundleItemPacketProxy extends PacketProxy {
    ServerboundSelectBundleItemPacketProxy INSTANCE = ASMProxyFactory.create(
            ServerboundSelectBundleItemPacketProxy.class
    );
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket");

    @MethodInvoker(name = "slotId")
    int slot(Object target);

    @MethodInvoker(name = "selectedItemIndex")
    int selectedItem(Object target);
}
