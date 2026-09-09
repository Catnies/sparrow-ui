package net.momirealms.sparrow.ui.proxy.bukkit.event.inventory;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import org.bukkit.event.inventory.InventoryCloseEvent;

@ReflectionProxy(clazz = InventoryCloseEvent.class, activeIf = "has_patch=paper")
public interface InventoryCloseEventProxy {
    InventoryCloseEventProxy INSTANCE = ASMProxyFactory.create(InventoryCloseEventProxy.class);

    @MethodInvoker(name = "getReason", activeIf = "has_patch=paper")
    Object getReason(InventoryCloseEvent target);
}
