package net.momirealms.sparrow.ui.visual.animation;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public interface AnimationHandle {

    /**
     * 取消这次动画播放并立即恢复被盖住的槽位显示.
     */
    void cancel();

    /**
     * 注册结束回调, 每次注册都恰好触发一次, 注册时已经结束则在当前线程立即触发.
     * <p><strong>回调可能在任意线程被调用, 内部只应使用线程安全的 API</strong>:
     * 自然播完在时钟线程触发, 取消在取消方线程, 窗口关闭在关闭流程的线程.
     * <p>不经关闭直接被 GC 回收时, 动画播放随宿主消亡, 这条路径不触发回调.
     *
     * @param callback 结束回调, 收到结束原因
     */
    void whenFinished(@NotNull Consumer<FinishReason> callback);

    /** 一次播放的结束原因. */
    enum FinishReason {
        COMPLETED,      // 时长走完, 自然播完
        CANCELLED,      // 被 {@link AnimationHandle#cancel} 取消
        WINDOW_CLOSED   // 所在窗口关闭, 只出现在 Window 作为播放宿主的动画上.
    }
}
