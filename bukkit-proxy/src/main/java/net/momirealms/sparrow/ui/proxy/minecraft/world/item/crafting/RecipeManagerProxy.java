package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.ui.proxy.minecraft.resources.ResourceKeyProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display.RecipeDisplayIdProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.util.Map;
import java.util.function.Consumer;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.RecipeManager")
public interface RecipeManagerProxy {
    RecipeManagerProxy INSTANCE = ASMProxyFactory.create(RecipeManagerProxy.class);

    @MethodInvoker(name = "getSynchronizedItemProperties", activeIf = "min_version=1.21.2")
    Map<?, ?> getSynchronizedItemProperties(Object target);

    @MethodInvoker(name = "getSynchronizedStonecutterRecipes", activeIf = "min_version=1.21.2")
    Object getSynchronizedStonecutterRecipes(Object target);

    @MethodInvoker(name = "getRecipeFromDisplay", activeIf = "min_version=1.21.2")
    Object getRecipeFromDisplay(
            Object target,
            @Type(clazz = RecipeDisplayIdProxy.class) Object displayId
    );

    @MethodInvoker(name = "listDisplaysForRecipe", activeIf = "min_version=1.21.2")
    void listDisplaysForRecipe(
            Object target,
            @Type(clazz = ResourceKeyProxy.class) Object recipe,
            Consumer<Object> output
    );
}
