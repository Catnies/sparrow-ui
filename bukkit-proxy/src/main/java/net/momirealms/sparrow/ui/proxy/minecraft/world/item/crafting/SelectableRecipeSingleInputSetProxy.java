package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.SelectableRecipe$SingleInputSet")
public interface SelectableRecipeSingleInputSetProxy {
    SelectableRecipeSingleInputSetProxy INSTANCE = ASMProxyFactory.create(SelectableRecipeSingleInputSetProxy.class);

    @ConstructorInvoker
    Object newInstance(List<?> entries);
}
