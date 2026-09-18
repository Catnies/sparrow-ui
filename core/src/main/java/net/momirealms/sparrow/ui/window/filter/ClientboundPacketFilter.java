package net.momirealms.sparrow.ui.window.filter;

import net.momirealms.sparrow.ui.network.PacketIdRegistry;
import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface ClientboundPacketFilter {

    /**
     * 声明当前 Window 要拦下哪些客户端包.
     * <p>这一层拦的是帧头的包 ID, 到派发这一步能拿到的东西只有它, 所以最便宜的判法就是先在这里筛一遍.
     * 注册名到 ID 的换算做一次就够, 入参因此直接给当前服务端的 {@link PacketIdRegistry}.
     *
     * @param packetIds 当前服务端的包 ID 注册表
     * @return 要拦下的包 ID
     */
    int[] suppressedPacketIds(@NotNull PacketIdRegistry packetIds);

    /**
     * 拿到解码出来的包对象再判一次, 有些包只看 ID 分不出该不该拦.
     *
     * @param packet 即将发给客户端的 NMS 包
     * @return 拦下时返回 true; 默认不额外拦, 只按包 ID 判
     */
    default boolean suppresses(@NotNull Object packet) {
        return false;
    }
}
