package net.momirealms.sparrow.ui.proxy.minecraft.world.item.trading;

import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentExactPredicateProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.item.trading.ItemCost", activeIf = "min_version=1.20.5")
public interface ItemCostProxy {
    ItemCostProxy INSTANCE = ASMProxyFactory.create(ItemCostProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.20.5")
    Object newInstance(
            @Type(name = "net.minecraft.core.Holder") Object item,
            int count,
            @Type(clazz = DataComponentExactPredicateProxy.class) Object components,
            @Type(name = "net.minecraft.world.item.ItemStack") Object itemStack
    );
}
