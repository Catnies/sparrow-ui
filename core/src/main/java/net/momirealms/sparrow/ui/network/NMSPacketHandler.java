package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface NMSPacketHandler {

    /**
     * 处理当前叶子包, 同一实例可被多个连接并发调用.
     * @param user 当前连接
     * @param event 当前根包共享的事件, 仅在同步回调内借用
     * @param packet 当前匹配的 NMS 对象, 可以是 bundle 叶子
     * @throws Exception 回调失败会终止传播并丢弃整个根包
     */
    void handle(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) throws Exception;
}
