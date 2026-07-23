package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerClickPacket")
public interface ServerboundContainerClickPacketProxy extends PacketProxy {
    ServerboundContainerClickPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerClickPacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ServerboundContainerClickPacket");

    @MethodInvoker(name = "containerId")
    int containerId(Object target);

    @MethodInvoker(name = "stateId")
    int stateId(Object target);

    @MethodInvoker(name = "slotNum")
    short slot(Object target);

    @MethodInvoker(name = "buttonNum")
    byte button(Object target);

    /**
     * 把版本相关的 NMS 点击动作收敛为 Proxy 自己的稳定枚举.
     *
     * @param target NMS ServerboundContainerClickPacket
     * @return 稳定容器输入动作
     */
    default ContainerInput containerInput(Object target) {
        return switch (this.rawContainerInput(target).name()) {
            case "PICKUP" -> ContainerInput.PICKUP;
            case "QUICK_MOVE" -> ContainerInput.QUICK_MOVE;
            case "SWAP" -> ContainerInput.SWAP;
            case "CLONE" -> ContainerInput.CLONE;
            case "THROW" -> ContainerInput.THROW;
            case "QUICK_CRAFT" -> ContainerInput.QUICK_CRAFT;
            case "PICKUP_ALL" -> ContainerInput.PICKUP_ALL;
            default -> ContainerInput.UNKNOWN;
        };
    }

    @MethodInvoker(name = "containerInput")
    Enum<?> rawContainerInput(Object target);

    @MethodInvoker(name = "changedSlots")
    Int2ObjectMap<Object> changedSlots(Object target);

    @MethodInvoker(name = "carriedItem")
    Object carried(Object target);

    /**
     * 客户端容器操作的稳定分类.
     */
    enum ContainerInput {
        PICKUP,
        QUICK_MOVE,
        SWAP,
        CLONE,
        THROW,
        QUICK_CRAFT,
        PICKUP_ALL,
        UNKNOWN
    }
}
