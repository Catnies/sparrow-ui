package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.ui.proxy.minecraft.resources.ResourceKeyProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display.RecipeDisplayIdProxy;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

import java.util.Map;
import java.util.function.Consumer;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.RecipeManager")
public interface RecipeManagerProxy {
    RecipeManagerProxy INSTANCE = ASMProxyFactory.create(RecipeManagerProxy.class);

    @MethodInvoker(name = "getSynchronizedItemProperties")
    Map<?, ?> getSynchronizedItemProperties(Object target);

    @MethodInvoker(name = "getSynchronizedStonecutterRecipes")
    Object getSynchronizedStonecutterRecipes(Object target);

    @MethodInvoker(name = "getRecipeFromDisplay")
    Object getRecipeFromDisplay(
            Object target,
            @Type(clazz = RecipeDisplayIdProxy.class) Object displayId
    );

    @MethodInvoker(name = "listDisplaysForRecipe")
    void listDisplaysForRecipe(
            Object target,
            @Type(clazz = ResourceKeyProxy.class) Object recipe,
            Consumer<Object> output
    );
}
