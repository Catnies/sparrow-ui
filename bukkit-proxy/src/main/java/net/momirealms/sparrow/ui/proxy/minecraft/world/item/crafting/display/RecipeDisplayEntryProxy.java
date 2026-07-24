package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.display.RecipeDisplayEntry")
public interface RecipeDisplayEntryProxy {
    RecipeDisplayEntryProxy INSTANCE = ASMProxyFactory.create(RecipeDisplayEntryProxy.class);

    @MethodInvoker(name = "display")
    Object display(Object target);
}
