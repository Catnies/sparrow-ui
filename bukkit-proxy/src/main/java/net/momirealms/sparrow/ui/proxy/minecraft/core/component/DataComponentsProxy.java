package net.momirealms.sparrow.ui.proxy.minecraft.core.component;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.core.component.DataComponents")
public interface DataComponentsProxy {
    DataComponentsProxy INSTANCE = ASMProxyFactory.create(DataComponentsProxy.class);

    @FieldGetter(name = "CUSTOM_NAME", isStatic = true)
    Object customName();

    @FieldGetter(name = "TOOLTIP_DISPLAY", isStatic = true)
    Object tooltipDisplay();

    @FieldGetter(name = "ITEM_MODEL", isStatic = true)
    Object itemModel();
}
