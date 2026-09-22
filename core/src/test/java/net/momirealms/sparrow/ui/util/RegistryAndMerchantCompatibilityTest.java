package net.momirealms.sparrow.ui.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.momirealms.sparrow.ui.proxy.MinecraftPredicate;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.CraftRegistryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.trading.MerchantOfferProxy;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.CraftRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class RegistryAndMerchantCompatibilityTest {
    @Test
    void registryProxyInitializesWithBothHistoricalPaperSignaturesAndSpigot() throws Exception {
        Keyed value = () -> NamespacedKey.minecraft("target");
        ResourceKey registry = new ResourceKey();
        for (String version : List.of("1.21.4", "1.21.6", "1.21.7", "26.2")) {
            CraftRegistryProxy proxy = proxyFor(CraftRegistryProxy.class, version, true);
            CraftRegistry.Lookup holder = (CraftRegistry.Lookup) (VersionHelper.parseVersionToInteger(version) >= 12107
                    ? proxy.bukkitToMinecraftHolder(value) : proxy.bukkitToMinecraftHolder$0(value, registry));
            assertSame(value, holder.value());
            assertSame(VersionHelper.parseVersionToInteger(version) >= 12107 ? null : registry, holder.key());
            assertSame(CraftRegistry.ACCESS, proxy.getMinecraftRegistry());
        }
        CraftRegistryProxy spigot = proxyFor(CraftRegistryProxy.class, "26.2", false);
        CraftRegistry.Lookup holder = (CraftRegistry.Lookup) spigot.bukkitToMinecraftHolder$0(value, registry);
        assertSame(value, holder.value());
        assertSame(registry, holder.key());
        assertSame(registry, spigot.getMinecraftRegistry(registry));
    }

    @Test
    void constructorsPreserveOfferFieldsAndOptionalSecondInput() throws Exception {
        ItemCost first = new ItemCost();
        for (boolean paper : List.of(true, false)) {
            MerchantOfferProxy proxy = proxyFor(MerchantOfferProxy.class, "26.2", paper);
            for (Optional<ItemCost> second : List.of(Optional.<ItemCost>empty(), Optional.of(new ItemCost()))) {
                MerchantOffer offer = (MerchantOffer) (paper
                        ? proxy.newInstance(first, second, ItemStack.EMPTY, 2, 7, false, -3, 5, 0.2F, 11, true)
                        : proxy.newInstance$0(first, second, ItemStack.EMPTY, 2, 7, false, -3, 5, 0.2F, 11));
                assertSame(first, offer.first);
                assertSame(second, offer.second);
                assertSame(ItemStack.EMPTY, offer.result);
                assertEquals(2, offer.uses);
                assertEquals(7, offer.maxUses);
                assertFalse(offer.rewardExp);
                assertEquals(-3, offer.specialPriceDiff);
                assertEquals(5, offer.demand);
                assertEquals(0.2F, offer.priceMultiplier);
                assertEquals(11, offer.xp);
                assertEquals(paper, offer.ignoreDiscounts);
            }
        }
    }

    private static <T> T proxyFor(Class<T> type, String version, boolean paper) throws Exception {
        Class<?> reflection = Class.forName("net.momirealms.sparrow.reflection.SReflection");
        var setPredicate = reflection.getMethod("setActivePredicate", Predicate.class);
        Object previous = reflection.getMethod("getFilter").invoke(null);
        setPredicate.invoke(null, new MinecraftPredicate(version, List.of(paper ? "paper" : "spigot")));
        try {
            return type.cast(Class.forName("net.momirealms.sparrow.reflection.proxy.ASMProxyFactory").getMethod("create", Class.class).invoke(null, type));
        } finally {
            setPredicate.invoke(null, previous);
        }
    }
}
