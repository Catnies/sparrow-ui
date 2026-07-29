package net.momirealms.sparrow.ui.proxy.minecraft.world.item.trading;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.item.trading.ItemCost")
public interface ItemCostProxy {
    ItemCostProxy INSTANCE = ASMProxyFactory.create(ItemCostProxy.class);

    @ConstructorInvoker
    Object newInstance(
            @Type(name = "net.minecraft.core.Holder") Object item,
            int count,
            @Type(name = "net.minecraft.core.component.DataComponentExactPredicate") Object components,
            @Type(name = "net.minecraft.world.item.ItemStack") Object itemStack
    );
}
