package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.NotNull;

/**
 * 一个可主动失效的视觉范围.
 * <p>失效只请求宿主在自己的渲染线程重新生成视觉结果, 不会在调用线程执行视觉映射或物品提供器.
 * Visual 不是通用的 Signal 回调宿主; {@link #bind(Signal)} 的固定含义就是在来源失效时调用 {@link #dirty()}.
 */
public interface Visual {

    /**
     * 标记此视觉范围需要重新渲染.
     */
    void dirty();

    /**
     * 在 Signal 后续失效时自动标脏此视觉范围.
     * <p>绑定不补发当前值, 也不会读取 Signal; 第一次标脏发生在 Signal 的下一次失效.
     * 绑定由视觉宿主持有, Signal 与返回的句柄都不会反向保活宿主; 丢弃句柄不会结束绑定.
     *
     * @param signal 失效来源
     * @return 可用于提前解绑的非持有型句柄
     */
    @NotNull
    Subscription bind(@NotNull Signal<?> signal);
}
