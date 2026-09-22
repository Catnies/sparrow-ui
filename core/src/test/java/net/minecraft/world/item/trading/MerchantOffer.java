package net.minecraft.world.item.trading;

import net.minecraft.world.item.ItemStack;
import java.util.Optional;

public final class MerchantOffer {
    public final ItemCost first;
    public final Optional<?> second;
    public final ItemStack result;
    public final int uses, maxUses, specialPriceDiff, demand, xp;
    public final boolean rewardExp, ignoreDiscounts;
    public final float priceMultiplier;

    private MerchantOffer(ItemCost first, Optional<?> second, ItemStack result, int uses, int maxUses, boolean rewardExp, int specialPriceDiff, int demand, float priceMultiplier, int xp) {
        this(first, second, result, uses, maxUses, rewardExp, specialPriceDiff, demand, priceMultiplier, xp, false);
    }

    private MerchantOffer(ItemCost first, Optional<?> second, ItemStack result, int uses, int maxUses, boolean rewardExp, int specialPriceDiff, int demand, float priceMultiplier, int xp, boolean ignoreDiscounts) {
        this.first = first;
        this.second = second;
        this.result = result;
        this.uses = uses;
        this.maxUses = maxUses;
        this.rewardExp = rewardExp;
        this.specialPriceDiff = specialPriceDiff;
        this.demand = demand;
        this.priceMultiplier = priceMultiplier;
        this.xp = xp;
        this.ignoreDiscounts = ignoreDiscounts;
    }
}
