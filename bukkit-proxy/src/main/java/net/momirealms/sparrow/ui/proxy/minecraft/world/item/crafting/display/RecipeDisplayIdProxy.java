package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.display.RecipeDisplayId")
public interface RecipeDisplayIdProxy {
    RecipeDisplayIdProxy INSTANCE = ASMProxyFactory.create(RecipeDisplayIdProxy.class);

    @ConstructorInvoker
    Object newInstance(int index);

    @MethodInvoker(name = "index")
    int index(Object target);
}
