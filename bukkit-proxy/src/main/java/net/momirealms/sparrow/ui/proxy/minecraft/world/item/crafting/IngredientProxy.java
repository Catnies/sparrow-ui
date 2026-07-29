package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.util.stream.Stream;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.Ingredient")
public interface IngredientProxy {
    IngredientProxy INSTANCE = ASMProxyFactory.create(IngredientProxy.class);

    @MethodInvoker(name = "of", isStatic = true)
    Object of(@Type(name = "java.util.stream.Stream") Stream<?> items);
}
