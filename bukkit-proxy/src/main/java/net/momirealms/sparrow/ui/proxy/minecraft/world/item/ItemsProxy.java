package net.momirealms.sparrow.ui.proxy.minecraft.world.item;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.Items")
public interface ItemsProxy {
    ItemsProxy INSTANCE = ASMProxyFactory.create(ItemsProxy.class);
    Object BARRIER = INSTANCE.BARRIER();
    Object FILLED_MAP = INSTANCE.FILLED_MAP();
    Object STONE = INSTANCE.STONE();
    Object PAPER = INSTANCE.PAPER();
    Object MAP = INSTANCE.MAP();
    Object GLASS_PANE = INSTANCE.GLASS_PANE();
    Object AIR = INSTANCE.AIR();

    @FieldGetter(name = "BARRIER", isStatic = true)
    Object BARRIER();

    @FieldGetter(name = "FILLED_MAP", isStatic = true)
    Object FILLED_MAP();

    @FieldGetter(name = "STONE", isStatic = true)
    Object STONE();

    @FieldGetter(name = "PAPER", isStatic = true)
    Object PAPER();

    @FieldGetter(name = "MAP", isStatic = true)
    Object MAP();

    @FieldGetter(name = "GLASS_PANE", isStatic = true)
    Object GLASS_PANE();

    @FieldGetter(name = "AIR", isStatic = true)
    Object AIR();
}
