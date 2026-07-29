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

    @FieldGetter(name = "containerMenu")
    Object containerMenu(Object target);

    @FieldSetter(name = "containerMenu")
    void containerMenu(Object target, Object menu);

    @FieldGetter(name = "inventoryMenu")
    Object inventoryMenu(Object target);

    @FieldGetter(name = "inventory")
    Object inventory(Object target);

    @FieldSetter(name = "inventory")
    void inventory(Object target, Object inventory);
}
