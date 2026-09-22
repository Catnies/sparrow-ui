package net.momirealms.sparrow.ui.proxy.minecraft.server.level;

import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player.PlayerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.util.function.Consumer;

@ReflectionProxy(name = "net.minecraft.server.level.ServerPlayer")
public interface ServerPlayerProxy extends PlayerProxy {
    ServerPlayerProxy INSTANCE = ASMProxyFactory.create(ServerPlayerProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.server.level.ServerPlayer");

    @FieldGetter(name = "chunkLoader", activeIf = "min_version=1.20.1 && has_patch=paper")
    Object getChunkLoader(Object target);

    @FieldGetter(name = "connection", activeIf = "min_version=1.20.1")
    Object connection(Object target);

    @MethodInvoker(name = "hasDisconnected", activeIf = "min_version=1.20.1")
    boolean hasDisconnected(Object target);

    @MethodInvoker(name = "drop", activeIf = "min_version=1.21.4 && has_patch=paper")
    Object drop(Object target, @Type(clazz = ItemStackProxy.class) Object item, boolean randomly, boolean thrownFromHand, boolean callEvent, Consumer<?> entityOperation);

    @MethodInvoker(name = "drop", activeIf = "min_version=26.2 && !has_patch=paper")
    Object drop$0(Object target, @Type(clazz = ItemStackProxy.class) Object item, boolean randomly, boolean thrownFromHand, boolean callEvent);

    @FieldGetter(name = "containerSynchronizer", activeIf = "min_version=1.20.1")
    Object containerSynchronizer(Object target);

    @MethodInvoker(name = "nextContainerCounter", activeIf = "min_version=1.20.1")
    int nextContainerCounter(Object target);

    @MethodInvoker(name = "closeContainer", activeIf = "min_version=1.20.1 && has_patch=paper")
    void closeContainer(Object target, @Type(name = "org.bukkit.event.inventory.InventoryCloseEvent$Reason") Object reason);

    @MethodInvoker(name = "closeContainer", activeIf = "min_version=1.20.1 && !has_patch=paper")
    void closeContainer$0(Object target);

    @MethodInvoker(name = "doCloseContainer", activeIf = "min_version=1.20.1")
    void doCloseContainer(Object target);
}
