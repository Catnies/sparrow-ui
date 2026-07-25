package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket")
public interface ClientboundMerchantOffersPacketProxy extends PacketProxy {
    ClientboundMerchantOffersPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundMerchantOffersPacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket");

    @ConstructorInvoker
    Object newInstance(
            int containerId,
            @Type(name = "net.minecraft.world.item.trading.MerchantOffers") Object offers,
            int villagerLevel,
            int villagerXp,
            boolean showProgress,
            boolean canRestock
    );
}
