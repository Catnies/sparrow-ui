package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.NotNull;

/**
 * 可主动标脏并跟随 Signal 失效的视觉配置.
 */
public interface Visual {

    /**
     * 标记此 Visual 需要重新渲染.
     */
    void dirty();

    /**
     * 在 Signal 后续失效时自动标脏此 Visual.
     *
     * @param signal 失效来源
     * @return 可用于提前解绑的非持有型句柄
     */
    @NotNull
    Subscription bind(@NotNull Signal<?> signal);
}
