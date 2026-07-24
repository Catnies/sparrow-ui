package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.display.RecipeDisplayId")
public interface RecipeDisplayIdProxy {
    RecipeDisplayIdProxy INSTANCE = ASMProxyFactory.create(RecipeDisplayIdProxy.class);

    @ConstructorInvoker
    Object newInstance(int index);

    @MethodInvoker(name = "index")
    int index(Object target);
}
