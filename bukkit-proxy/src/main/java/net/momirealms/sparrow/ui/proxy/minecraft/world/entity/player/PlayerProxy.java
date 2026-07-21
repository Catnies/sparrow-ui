package net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.FieldSetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * Minecraft 玩家持有的活动菜单、库存菜单和物品栏访问代理.
 */
@ReflectionProxy(name = "net.minecraft.world.entity.player.Player")
public interface PlayerProxy {
    PlayerProxy INSTANCE = ASMProxyFactory.create(PlayerProxy.class);

    @FieldGetter(name = "containerMenu")
    Object containerMenu(Object target);

    @FieldSetter(name = "containerMenu")
    void containerMenu(Object target, Object menu);

    @FieldGetter(name = "inventoryMenu")
    Object inventoryMenu(Object target);

    @FieldGetter(name = "inventory")
    Object inventory(Object target);
}
