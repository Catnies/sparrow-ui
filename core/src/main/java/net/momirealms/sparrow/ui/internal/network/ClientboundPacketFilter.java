package net.momirealms.sparrow.ui.internal.network;

import org.jetbrains.annotations.NotNull;

/**
 * ClientboundPacket 过滤器,
 * 代表在菜单会话期间过滤发出的数据包.
 */
@FunctionalInterface
public interface ClientboundPacketFilter {

    /**
     * 判断当前 Window 是否要拦下这个原版包.
     *
     * @param packet 即将发送的原版 NMS 客户端包
     * @return 要拦下时返回 true, 否则让原版照常发送
     */
    boolean suppresses(@NotNull Object packet);
}
