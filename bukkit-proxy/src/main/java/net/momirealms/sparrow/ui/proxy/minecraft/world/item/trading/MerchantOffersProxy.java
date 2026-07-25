package net.momirealms.sparrow.ui.proxy.minecraft.world.item.trading;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

import java.util.Collection;

@ReflectionProxy(name = "net.minecraft.world.item.trading.MerchantOffers")
public interface MerchantOffersProxy {
    MerchantOffersProxy INSTANCE = ASMProxyFactory.create(MerchantOffersProxy.class);

    @ConstructorInvoker
    Object newInstance(Collection<?> offers);
}
