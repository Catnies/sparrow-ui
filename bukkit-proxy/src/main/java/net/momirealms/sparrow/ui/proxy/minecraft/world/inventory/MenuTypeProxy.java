package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.inventory.MenuType")
public interface MenuTypeProxy {
    MenuTypeProxy INSTANCE = ASMProxyFactory.create(MenuTypeProxy.class);
    Object GENERIC_9x1 = INSTANCE.GENERIC_9x1();
    Object GENERIC_9x2 = INSTANCE.GENERIC_9x2();
    Object GENERIC_9x3 = INSTANCE.GENERIC_9x3();
    Object GENERIC_9x4 = INSTANCE.GENERIC_9x4();
    Object GENERIC_9x5 = INSTANCE.GENERIC_9x5();
    Object GENERIC_9x6 = INSTANCE.GENERIC_9x6();
    Object HOPPER = INSTANCE.HOPPER();
    Object ANVIL = INSTANCE.ANVIL();
    Object GENERIC_3x3 = INSTANCE.GENERIC_3x3();
    Object GRINDSTONE = INSTANCE.GRINDSTONE();
    Object SMITHING = INSTANCE.SMITHING();
    Object BREWING_STAND = INSTANCE.BREWING_STAND();
    Object CARTOGRAPHY_TABLE = INSTANCE.CARTOGRAPHY_TABLE();

    @FieldGetter(name = "GENERIC_9x1", isStatic = true)
    Object GENERIC_9x1();

    @FieldGetter(name = "GENERIC_9x2", isStatic = true)
    Object GENERIC_9x2();

    @FieldGetter(name = "GENERIC_9x3", isStatic = true)
    Object GENERIC_9x3();

    @FieldGetter(name = "GENERIC_9x4", isStatic = true)
    Object GENERIC_9x4();

    @FieldGetter(name = "GENERIC_9x5", isStatic = true)
    Object GENERIC_9x5();

    @FieldGetter(name = "GENERIC_9x6", isStatic = true)
    Object GENERIC_9x6();

    @FieldGetter(name = "HOPPER", isStatic = true)
    Object HOPPER();

    @FieldGetter(name = "ANVIL", isStatic = true)
    Object ANVIL();

    @FieldGetter(name = "GENERIC_3x3", isStatic = true)
    Object GENERIC_3x3();

    @FieldGetter(name = "GRINDSTONE", isStatic = true)
    Object GRINDSTONE();

    @FieldGetter(name = "SMITHING", isStatic = true)
    Object SMITHING();

    @FieldGetter(name = "BREWING_STAND", isStatic = true)
    Object BREWING_STAND();

    @FieldGetter(name = "CARTOGRAPHY_TABLE", isStatic = true)
    Object CARTOGRAPHY_TABLE();
}
