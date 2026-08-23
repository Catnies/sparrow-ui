package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.display.SlotDisplay$ItemStackSlotDisplay", activeIf = "min_version=1.21.2")
public interface ItemStackSlotDisplayProxy {
    ItemStackSlotDisplayProxy INSTANCE = ASMProxyFactory.create(ItemStackSlotDisplayProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.21.2 && max_version=1.21.11")
    Object newInstance(@Type(name = "net.minecraft.world.item.ItemStack") Object stack);

    @ConstructorInvoker(activeIf = "min_version=26.1")
    Object newInstance$0(@Type(name = "net.minecraft.world.item.ItemStackTemplate") Object stack);
}
