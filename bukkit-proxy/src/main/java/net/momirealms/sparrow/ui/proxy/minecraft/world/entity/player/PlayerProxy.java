package net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.entity.player.Player")
public interface PlayerProxy {
    PlayerProxy INSTANCE = ASMProxyFactory.create(PlayerProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.world.entity.player.Player");

    @FieldGetter(name = "containerMenu", activeIf = "min_version=1.20.1")
    Object containerMenu(Object target);

    @FieldSetter(name = "containerMenu", activeIf = "min_version=1.20.1")
    void containerMenu(Object target, Object menu);

    @FieldGetter(name = "inventoryMenu", activeIf = "min_version=1.20.1")
    Object inventoryMenu(Object target);

    @FieldGetter(name = "inventory", activeIf = "min_version=1.20.1")
    Object inventory(Object target);

    @FieldSetter(name = "inventory", activeIf = "min_version=1.20.1")
    void inventory(Object target, Object inventory);
}
