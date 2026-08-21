package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.visual.animation.ActivePlayback;
import net.momirealms.sparrow.ui.visual.animation.TitleAnimationDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class ActiveTitleAnimation extends ActivePlayback<AbstractWindow<?>> {
    static final ActiveTitleAnimation[] NONE = new ActiveTitleAnimation[0]; // 空数组, 避免每次新建

    private final TitleAnimationDefinition animationDefinition; // 动画定义

    ActiveTitleAnimation(@NotNull AbstractWindow<?> host, @NotNull TitleAnimationDefinition animationDefinition, long startTick) {
        super(host, startTick, animationDefinition.totalTicks());
        this.animationDefinition = animationDefinition;
    }

    @Nullable
    Component frameAt(long nowTick) {
        return this.animationDefinition.frame(nowTick - this.startTick());
    }

    @Override
    protected void advanceFrame(@NotNull AbstractWindow<?> host) {
        host.notifyTitleAnimationChanged();
    }

    @Override
    protected void detach(@NotNull AbstractWindow<?> host) {
        host.removeTitleAnimation(this);
    }
}
