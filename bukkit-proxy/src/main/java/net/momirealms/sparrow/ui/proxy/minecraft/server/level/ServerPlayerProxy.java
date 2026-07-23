package net.momirealms.sparrow.ui.proxy.minecraft.server.level;

import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player.PlayerProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * SparrowUI 打开容器时使用的 ServerPlayer 状态访问代理.
 */
@ReflectionProxy(name = "net.minecraft.server.level.ServerPlayer")
public interface ServerPlayerProxy extends PlayerProxy {
    ServerPlayerProxy INSTANCE = ASMProxyFactory.create(ServerPlayerProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.server.level.ServerPlayer");

    @FieldGetter(name = "chunkLoader", activeIf = "has_patch=paper")
    Object getChunkLoader(Object target);

    @FieldGetter(name = "connection")
    Object connection(Object target);

    @FieldGetter(name = "containerSynchronizer")
    Object containerSynchronizer(Object target);

    @MethodInvoker(name = "nextContainerCounter")
    int nextContainerCounter(Object target);

    @MethodInvoker(name = "closeContainer")
    void closeContainer(Object target, InventoryCloseEvent.Reason reason);

    @MethodInvoker(name = "doCloseContainer")
    void doCloseContainer(Object target);
}
