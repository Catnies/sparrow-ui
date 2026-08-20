package net.momirealms.sparrow.ui.visual.animation;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

record TitleFrameFunctionAnimation(
        long periodTicks,
        long totalTicks,
        @NotNull TitleFrameFunction frameFunction
) implements TitleAnimationDefinition {

    @Override
    @Nullable
    public Component frame(long elapsedTicks) {
        return this.frameFunction.frame(elapsedTicks);
    }
}
