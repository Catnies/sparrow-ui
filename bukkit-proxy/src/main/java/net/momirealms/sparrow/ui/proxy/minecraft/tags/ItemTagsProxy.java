package net.momirealms.sparrow.ui.proxy.minecraft.tags;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.tags.ItemTags")
public interface ItemTagsProxy {
    ItemTagsProxy INSTANCE = ASMProxyFactory.create(ItemTagsProxy.class);
    Object BUNDLES = INSTANCE.BUNDLES();

    @FieldGetter(name = "BUNDLES", isStatic = true, activeIf = "min_version=1.21.2")
    Object BUNDLES();
}
