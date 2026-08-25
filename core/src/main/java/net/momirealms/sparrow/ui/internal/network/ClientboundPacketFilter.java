package net.momirealms.sparrow.ui.internal.network;

import net.momirealms.sparrow.ui.network.PacketIdRegistry;
import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface ClientboundPacketFilter {

    /**
     * 声明当前 Window 要拦下的客户端包 ID.
     *
     * @param packetIds 当前服务端的包 ID 注册表
     * @return 要拦下的包 ID
     */
    int[] suppressedPacketIds(@NotNull PacketIdRegistry packetIds);

    /**
     * 判断当前 Window 是否要拦下这个 NMS 包对象.
     *
     * @param packet 即将发送的原版 NMS 客户端包
     * @return 要拦下时返回 true, 否则让原版照常发送
     */
    default boolean suppresses(@NotNull Object packet) {
        return false;
    }
}
