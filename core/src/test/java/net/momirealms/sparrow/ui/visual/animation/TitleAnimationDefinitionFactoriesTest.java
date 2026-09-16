package net.momirealms.sparrow.ui.visual.animation;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleAnimationDefinitionFactoriesTest {

    @Test
    void framesAdvanceByPeriodAndYieldAfterTheLastFrame() {
        Component first = Component.text("one");
        Component second = Component.text("two");
        TitleAnimationDefinition definition = TitleAnimationDefinition.frames(5, List.of(first, second));

        assertEquals(5, definition.periodTicks());
        assertEquals(10, definition.totalTicks(), "总时长 = 帧数 × 周期");
        assertSame(first, definition.frame(0));
        assertSame(first, definition.frame(4), "同一帧期间给出同一个实例");
        assertSame(second, definition.frame(5));
        assertSame(second, definition.frame(9));
        assertNull(definition.frame(10), "走完最后一帧即放行");
    }

    @Test
    void loopWrapsAroundAndNeverEnds() {
        Component red = Component.text("red");
        Component green = Component.text("green");
        Component blue = Component.text("blue");
        TitleAnimationDefinition definition = TitleAnimationDefinition.loop(2, List.of(red, green, blue));

        assertTrue(definition.totalTicks() < 0, "循环动画无限时长");
        assertSame(red, definition.frame(0));
        assertSame(green, definition.frame(2));
        assertSame(blue, definition.frame(4));
        assertSame(red, definition.frame(6), "走满一轮回到第一帧");
        assertSame(green, definition.frame(128));
    }

    @Test
    void frameFunctionFormPassesElapsedThrough() {
        TitleAnimationDefinition definition = TitleAnimationDefinition.of(3, 9, elapsedTicks ->
                elapsedTicks < 6 ? Component.text("tick-" + elapsedTicks) : null);

        assertEquals(3, definition.periodTicks());
        assertEquals(9, definition.totalTicks());
        assertEquals(Component.text("tick-0"), definition.frame(0));
        assertEquals(Component.text("tick-4"), definition.frame(4));
        assertNull(definition.frame(6), "帧函数返回 null 即放行");
    }

    @Test
    void factoriesRejectIllegalArguments() {
        List<Component> frames = List.of(Component.text("only"));

        assertThrows(IllegalArgumentException.class, () -> TitleAnimationDefinition.of(0, -1, elapsedTicks -> null));
        assertThrows(IllegalArgumentException.class, () -> TitleAnimationDefinition.frames(0, frames));
        assertThrows(IllegalArgumentException.class, () -> TitleAnimationDefinition.frames(1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> TitleAnimationDefinition.loop(-2, frames));
        assertThrows(IllegalArgumentException.class, () -> TitleAnimationDefinition.loop(1, List.of()));
        List<Component> withNull = Arrays.asList(Component.text("one"), null);

        assertThrows(NullPointerException.class, () -> TitleAnimationDefinition.frames(1, withNull));
    }

    @Test
    void frameSequenceIsFixedAtCreation() {
        List<Component> mutable = new ArrayList<>(List.of(Component.text("kept")));
        TitleAnimationDefinition definition = TitleAnimationDefinition.loop(1, mutable);
        mutable.set(0, Component.text("changed"));

        assertEquals(Component.text("kept"), definition.frame(0), "帧序列在构造时定死, 之后改列表不影响播放");
    }
}
