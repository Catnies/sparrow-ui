package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.inventory.MenuType")
public interface MenuTypeProxy {
    MenuTypeProxy INSTANCE = ASMProxyFactory.create(MenuTypeProxy.class);

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
}
