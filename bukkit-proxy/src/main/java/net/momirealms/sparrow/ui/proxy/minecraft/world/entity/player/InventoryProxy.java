package net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * 玩家物品栏版本计数的访问代理.
 */
@ReflectionProxy(name = "net.minecraft.world.entity.player.Inventory")
public interface InventoryProxy {
    InventoryProxy INSTANCE = ASMProxyFactory.create(InventoryProxy.class);

    @MethodInvoker(name = "getTimesChanged")
    int timesChanged(Object target);
}
