package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.display.RecipeDisplayEntry")
public interface RecipeDisplayEntryProxy {
    RecipeDisplayEntryProxy INSTANCE = ASMProxyFactory.create(RecipeDisplayEntryProxy.class);

    @MethodInvoker(name = "display")
    Object display(Object target);
}
