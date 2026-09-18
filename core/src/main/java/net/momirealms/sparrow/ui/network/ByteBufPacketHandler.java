package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface ByteBufPacketHandler {

    /**
     * 读取、改写或取消当前帧, 同一实例可被多个连接并发调用.
     * @param user 当前连接
     * @param event 本次派发内借用的事件
     * @throws Exception 只读失败报告后继续; 写操作或取消后的失败会丢弃帧
     */
    void handle(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) throws Exception;
}
