package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

import java.util.Map;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.RecipeManager")
public interface RecipeManagerProxy {
    RecipeManagerProxy INSTANCE = ASMProxyFactory.create(RecipeManagerProxy.class);

    @MethodInvoker(name = "getSynchronizedItemProperties")
    Map<?, ?> getSynchronizedItemProperties(Object target);

    @MethodInvoker(name = "getSynchronizedStonecutterRecipes")
    Object getSynchronizedStonecutterRecipes(Object target);
}
