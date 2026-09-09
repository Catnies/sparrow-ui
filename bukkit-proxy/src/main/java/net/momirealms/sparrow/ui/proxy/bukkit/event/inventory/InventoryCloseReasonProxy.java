package net.momirealms.sparrow.ui.proxy.bukkit.event.inventory;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "org.bukkit.event.inventory.InventoryCloseEvent$Reason", activeIf = "has_patch=paper")
public interface InventoryCloseReasonProxy {
    InventoryCloseReasonProxy INSTANCE = ASMProxyFactory.create(InventoryCloseReasonProxy.class);

    @MethodInvoker(name = "valueOf", isStatic = true, activeIf = "has_patch=paper")
    Object valueOf(String name);
}
