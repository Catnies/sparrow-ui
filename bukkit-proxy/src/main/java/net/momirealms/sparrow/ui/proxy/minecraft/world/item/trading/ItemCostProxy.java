package net.momirealms.sparrow.ui.proxy.minecraft.world.item.trading;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

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
