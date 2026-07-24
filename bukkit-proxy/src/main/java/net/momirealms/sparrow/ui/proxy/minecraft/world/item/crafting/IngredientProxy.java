package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

import java.util.stream.Stream;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.Ingredient")
public interface IngredientProxy {
    IngredientProxy INSTANCE = ASMProxyFactory.create(IngredientProxy.class);

    @MethodInvoker(name = "of", isStatic = true)
    Object of(@Type(name = "java.util.stream.Stream") Stream<?> items);
}
