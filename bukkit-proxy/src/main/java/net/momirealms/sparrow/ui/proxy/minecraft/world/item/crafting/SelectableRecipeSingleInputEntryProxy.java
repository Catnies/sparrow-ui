package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.SelectableRecipe$SingleInputEntry")
public interface SelectableRecipeSingleInputEntryProxy {
    SelectableRecipeSingleInputEntryProxy INSTANCE = ASMProxyFactory.create(SelectableRecipeSingleInputEntryProxy.class);

    @ConstructorInvoker
    Object newInstance(
            @Type(name = "net.minecraft.world.item.crafting.Ingredient") Object input,
            @Type(name = "net.minecraft.world.item.crafting.SelectableRecipe") Object recipe
    );
}
