package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.SelectableRecipe$SingleInputSet")
public interface SelectableRecipeSingleInputSetProxy {
    SelectableRecipeSingleInputSetProxy INSTANCE = ASMProxyFactory.create(SelectableRecipeSingleInputSetProxy.class);

    @ConstructorInvoker
    Object newInstance(List<?> entries);
}
