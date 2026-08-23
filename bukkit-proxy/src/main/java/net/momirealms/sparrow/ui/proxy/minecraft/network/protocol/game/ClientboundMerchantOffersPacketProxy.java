package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket")
public interface ClientboundMerchantOffersPacketProxy extends PacketProxy {
    ClientboundMerchantOffersPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundMerchantOffersPacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket");

    @ConstructorInvoker(activeIf = "min_version=1.20.1")
    Object newInstance(
            int containerId,
            @Type(name = "net.minecraft.world.item.trading.MerchantOffers") Object offers,
            int villagerLevel,
            int villagerXp,
            boolean showProgress,
            boolean canRestock
    );
}
