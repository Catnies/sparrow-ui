package net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.entity.player.Inventory")
public interface InventoryProxy {
    InventoryProxy INSTANCE = ASMProxyFactory.create(InventoryProxy.class);

    @MethodInvoker(name = "getTimesChanged", activeIf = "min_version=1.20.1")
    int timesChanged(Object target);
}
