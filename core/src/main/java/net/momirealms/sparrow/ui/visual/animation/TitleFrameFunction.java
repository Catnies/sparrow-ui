package net.momirealms.sparrow.ui.visual.animation;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 给定已经播过的 tick 数, 算出此刻盖在标题上的内容.
 */
@FunctionalInterface
public interface TitleFrameFunction {

    /**
     * 算出此刻的标题帧, 纯性与开销的约定见 {@link TitleAnimationDefinition#frame}.
     *
     * @param elapsedTicks 从播放开始经过的 tick 数
     * @return 此刻的标题帧; 此刻放行时给 {@code null}
     */
    @Nullable
    Component frame(long elapsedTicks);
}
