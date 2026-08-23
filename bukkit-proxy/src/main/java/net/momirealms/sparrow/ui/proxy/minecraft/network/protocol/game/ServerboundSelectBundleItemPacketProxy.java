package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket", activeIf = "min_version=1.21.2")
public interface ServerboundSelectBundleItemPacketProxy extends PacketProxy {
    ServerboundSelectBundleItemPacketProxy INSTANCE = ASMProxyFactory.create(
            ServerboundSelectBundleItemPacketProxy.class
    );
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket");

    @MethodInvoker(name = "slotId", activeIf = "min_version=1.21.2")
    int slot(Object target);

    @MethodInvoker(name = "selectedItemIndex", activeIf = "min_version=1.21.2")
    int selectedItem(Object target);
}
