package net.momirealms.sparrow.ui.proxy.minecraft.world.item;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

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
    Object BUNDLE = INSTANCE.BUNDLE();

    @FieldGetter(name = "BARRIER", isStatic = true, activeIf = "min_version=1.20.1")
    Object BARRIER();

    @FieldGetter(name = "FILLED_MAP", isStatic = true, activeIf = "min_version=1.20.1")
    Object FILLED_MAP();

    @FieldGetter(name = "STONE", isStatic = true, activeIf = "min_version=1.20.1")
    Object STONE();

    @FieldGetter(name = "PAPER", isStatic = true, activeIf = "min_version=1.20.1")
    Object PAPER();

    @FieldGetter(name = "MAP", isStatic = true, activeIf = "min_version=1.20.1")
    Object MAP();

    @FieldGetter(name = "GLASS_PANE", isStatic = true, activeIf = "min_version=1.20.1")
    Object GLASS_PANE();

    @FieldGetter(name = "AIR", isStatic = true, activeIf = "min_version=1.20.1")
    Object AIR();

    @FieldGetter(name = "BUNDLE", isStatic = true, activeIf = "min_version=1.20.1")
    Object BUNDLE();
}
