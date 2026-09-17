package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.visual.animation.ActivePlayback;
import net.momirealms.sparrow.ui.visual.animation.TitleAnimationDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class ActiveTitleAnimation extends ActivePlayback<AbstractWindow<?>> {
    static final ActiveTitleAnimation[] NONE = new ActiveTitleAnimation[0];

    private final TitleAnimationDefinition animationDefinition; // 这次播放的描述, 每一帧都向它要

    ActiveTitleAnimation(@NotNull AbstractWindow<?> host, @NotNull TitleAnimationDefinition animationDefinition, long startTick) {
        super(host, startTick, animationDefinition.totalTicks());
        this.animationDefinition = animationDefinition;
    }

    // 要此刻的标题帧; 放行时给 null, 由窗口那边决定露出配置标题
    @Nullable
    Component frameAt(long nowTick) {
        return this.animationDefinition.frame(nowTick - this.startTick());
    }

    // 换帧了, 让窗口把标题重发一遍
    @Override
    protected void advanceFrame(@NotNull AbstractWindow<?> host) {
        host.notifyTitleAnimationChanged();
    }

    // 播放结束: 把自己从窗口的标题动画通道里摘出来
    @Override
    protected void detach(@NotNull AbstractWindow<?> host) {
        host.removeTitleAnimation(this);
    }
}
