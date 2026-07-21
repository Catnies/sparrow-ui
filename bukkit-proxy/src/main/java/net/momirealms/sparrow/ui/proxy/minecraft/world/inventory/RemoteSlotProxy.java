package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.network.HashedStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

/**
 * Paper RemoteSlot 客户端哈希和权威物品镜像的访问代理.
 */
@ReflectionProxy(name = "net.minecraft.world.inventory.RemoteSlot")
public interface RemoteSlotProxy {
    RemoteSlotProxy INSTANCE = ASMProxyFactory.create(RemoteSlotProxy.class);

    @MethodInvoker(name = "receive")
    void receive(Object target, @Type(clazz = HashedStackProxy.class) Object hashedStack);

    @MethodInvoker(name = "matches")
    boolean matches(Object target, @Type(clazz = ItemStackProxy.class) Object item);

    @MethodInvoker(name = "force")
    void force(Object target, @Type(clazz = ItemStackProxy.class) Object item);
}
