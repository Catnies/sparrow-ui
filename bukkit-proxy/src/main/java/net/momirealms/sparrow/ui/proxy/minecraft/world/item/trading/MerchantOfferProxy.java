package net.momirealms.sparrow.ui.proxy.minecraft.world.item.trading;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

import java.util.Optional;

@ReflectionProxy(name = "net.minecraft.world.item.trading.MerchantOffer")
public interface MerchantOfferProxy {
    MerchantOfferProxy INSTANCE = ASMProxyFactory.create(MerchantOfferProxy.class);

    @ConstructorInvoker
    Object newInstance(
            @Type(name = "net.minecraft.world.item.trading.ItemCost") Object baseCostA,
            Optional<?> costB,
            @Type(name = "net.minecraft.world.item.ItemStack") Object result,
            int uses,
            int maxUses,
            boolean rewardExp,
            int specialPriceDiff,
            int demand,
            float priceMultiplier,
            int xp,
            boolean ignoreDiscounts
    );
}
