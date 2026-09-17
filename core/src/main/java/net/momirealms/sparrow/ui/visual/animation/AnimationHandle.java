package net.momirealms.sparrow.ui.visual.animation;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public interface AnimationHandle {

    /**
     * 取消这次播放, 被它盖住的显示立刻恢复.
     */
    void cancel();

    /**
     * 注册结束回调, 每次注册都会恰好收到一次.
     * <p>注册的时候播放已经结束了的话, 就在当前线程立刻回调.
     * <p><strong>回调可能在任意线程被调用, 里面只该用线程安全的 API.</strong>
     * 自然播完在时钟线程回调, 取消在发起取消的那个线程, 窗口关闭在关闭流程的线程.
     * <p>宿主不经关闭直接被回收时, 播放随宿主一起消失, 这条路径不回调.
     *
     * @param callback 结束回调, 收到结束原因
     */
    void whenFinished(@NotNull Consumer<FinishReason> callback);

    /** 这次播放是怎么结束的. */
    enum FinishReason {
        COMPLETED,      // 时长走完, 自然播完
        CANCELLED,      // 被 {@link AnimationHandle#cancel} 取消
        WINDOW_CLOSED   // 所在窗口关闭; 只有 Window 做宿主的播放会有这个原因
    }
}
