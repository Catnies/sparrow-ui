package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.display.RecipeDisplayEntry", activeIf = "min_version=1.21.2")
public interface RecipeDisplayEntryProxy {
    RecipeDisplayEntryProxy INSTANCE = ASMProxyFactory.create(RecipeDisplayEntryProxy.class);

    @MethodInvoker(name = "display", activeIf = "min_version=1.21.2")
    Object display(Object target);
}
