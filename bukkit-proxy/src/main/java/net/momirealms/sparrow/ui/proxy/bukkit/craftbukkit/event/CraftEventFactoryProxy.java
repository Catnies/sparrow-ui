package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.event;

import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player.PlayerProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import org.bukkit.event.inventory.InventoryCloseEvent;

@ReflectionProxy(name = "org.bukkit.craftbukkit.event.CraftEventFactory")
public interface CraftEventFactoryProxy {
    CraftEventFactoryProxy INSTANCE = ASMProxyFactory.create(CraftEventFactoryProxy.class);

    @MethodInvoker(name = "handleInventoryCloseEvent", isStatic = true)
    void handleInventoryCloseEvent(
            @Type(clazz = PlayerProxy.class) Object player,
            InventoryCloseEvent.Reason reason
    );
}
