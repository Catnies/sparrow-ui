package net.momirealms.sparrow.ui.visual.animation;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface TitleFrameFunction {

    /**
     * 求值此刻的标题帧, 契约见 {@link TitleAnimationDefinition#frame}.
     *
     * @param elapsedTicks 从播放开始经过的 tick 数
     * @return 此刻的标题帧, {@code null} 表示此刻放行
     */
    @Nullable
    Component frame(long elapsedTicks);
}
