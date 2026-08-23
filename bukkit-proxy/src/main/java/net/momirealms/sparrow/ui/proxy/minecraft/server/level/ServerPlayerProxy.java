package net.momirealms.sparrow.ui.proxy.minecraft.server.level;

import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player.PlayerProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import org.bukkit.event.inventory.InventoryCloseEvent;

@ReflectionProxy(name = "net.minecraft.server.level.ServerPlayer")
public interface ServerPlayerProxy extends PlayerProxy {
    ServerPlayerProxy INSTANCE = ASMProxyFactory.create(ServerPlayerProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.server.level.ServerPlayer");

    @FieldGetter(name = "chunkLoader", activeIf = "min_version=1.20.1 && has_patch=paper")
    Object getChunkLoader(Object target);

    @FieldGetter(name = "connection", activeIf = "min_version=1.20.1")
    Object connection(Object target);

    @FieldGetter(name = "containerSynchronizer", activeIf = "min_version=1.20.1")
    Object containerSynchronizer(Object target);

    @MethodInvoker(name = "nextContainerCounter", activeIf = "min_version=1.20.1")
    int nextContainerCounter(Object target);

    @MethodInvoker(name = "closeContainer", activeIf = "min_version=1.20.1")
    void closeContainer(Object target, InventoryCloseEvent.Reason reason);

    @MethodInvoker(name = "doCloseContainer", activeIf = "min_version=1.20.1")
    void doCloseContainer(Object target);
}
