package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.network.HashedStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.inventory.RemoteSlot", activeIf = "min_version=1.21.2")
public interface RemoteSlotProxy {
    RemoteSlotProxy INSTANCE = ASMProxyFactory.create(RemoteSlotProxy.class);

    @MethodInvoker(name = "receive", activeIf = "min_version=1.21.2")
    void receive(Object target, @Type(clazz = HashedStackProxy.class) Object hashedStack);

    @MethodInvoker(name = "matches", activeIf = "min_version=1.21.2")
    boolean matches(Object target, @Type(clazz = ItemStackProxy.class) Object item);

    @MethodInvoker(name = "force", activeIf = "min_version=1.21.2")
    void force(Object target, @Type(clazz = ItemStackProxy.class) Object item);
}
