package net.momirealms.sparrow.ui.proxy.minecraft.core.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.component.DataComponents")
public interface DataComponentsProxy {
    DataComponentsProxy INSTANCE = ASMProxyFactory.create(DataComponentsProxy.class);
    Object CUSTOM_NAME = INSTANCE.CUSTOM_NAME();
    Object TOOLTIP_DISPLAY = INSTANCE.TOOLTIP_DISPLAY();
    Object ITEM_MODEL = INSTANCE.ITEM_MODEL();
    Object MAP_ID = INSTANCE.MAP_ID();

    @FieldGetter(name = "CUSTOM_NAME", isStatic = true)
    Object CUSTOM_NAME();

    @FieldGetter(name = "TOOLTIP_DISPLAY", isStatic = true)
    Object TOOLTIP_DISPLAY();

    @FieldGetter(name = "ITEM_MODEL", isStatic = true)
    Object ITEM_MODEL();

    @FieldGetter(name = "MAP_ID", isStatic = true)
    Object MAP_ID();
}
