package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.pane.SlotSequence;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationDefinitionFactoriesTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void framesAdvanceByPeriodAndYieldAfterTheLastFrame() {
        AnimationDefinition definition = AnimationDefinition.frames(new int[]{4, 7}, 5, List.of(item(Material.DIAMOND), item(Material.EMERALD)));

        assertArrayEquals(new int[]{4, 7}, definition.slots());
        assertEquals(5, definition.periodTicks());
        assertEquals(10, definition.totalTicks(), "总时长 = 帧数 × 周期");
        ItemProvider first = definition.frame(0, 4, 0, null);

        assertNotNull(first);
        assertSame(first, definition.frame(0, 4, 4, null), "同一帧期间给出同一个提供器实例");
        assertSame(first, definition.frame(1, 7, 3, null), "全部槽位同步显示同一帧");
        ItemProvider second = definition.frame(0, 4, 5, null);

        assertNotNull(second);
        assertNotSame(first, second);
        assertSame(second, definition.frame(0, 4, 9, null));
        assertNull(definition.frame(0, 4, 10, null), "走完最后一帧即放行");
    }

    @Test
    void revealCoversUntilEachSlotsTurnThenYields() {
        AnimationDefinition definition = AnimationDefinition.reveal(new int[]{4, 0, 8}, 3, item(Material.GRAY_STAINED_GLASS_PANE));

        assertEquals(3, definition.periodTicks(), "逐格出现的推进周期就是出现间隔");
        assertEquals(6, definition.totalTicks(), "最后一槽放行即整体结束");
        assertNull(definition.frame(0, 4, 0, null), "第一个槽位开场即放行");
        ItemProvider cover = definition.frame(1, 0, 0, null);

        assertNotNull(cover, "未轮到的槽位显示盖层");
        assertSame(cover, definition.frame(2, 8, 0, null), "全部槽位共用同一个盖层提供器");
        assertSame(cover, definition.frame(1, 0, 2, null));
        assertNull(definition.frame(1, 0, 3, null), "轮到即放行");
        assertSame(cover, definition.frame(2, 8, 5, null));
        assertNull(definition.frame(2, 8, 6, null));
    }

    @Test
    void revealWithoutCoverKeepsOnlyTheTimeline() {
        AnimationDefinition definition = AnimationDefinition.reveal(new int[]{0, 1}, 4, null);

        assertNull(definition.frame(1, 1, 0, null), "没有盖层时未轮到也放行");
        assertEquals(4, definition.totalTicks(), "时间轴仍然存在, 结束回调照常有效");
    }

    @Test
    void staggeredFramesWalkCoverPlayingAndYieldPerSlot() {
        AnimationDefinition definition = AnimationDefinition.staggeredFrames(new int[]{3, 5}, 4, 2, List.of(item(Material.TNT), item(Material.FIRE_CHARGE)), item(Material.BARRIER));

        assertEquals(2, definition.periodTicks());
        assertEquals(8, definition.totalTicks(), "总时长 = 间隔 × (槽数 − 1) + 帧数 × 周期");
        ItemProvider pending = definition.frame(1, 5, 0, null);

        assertNotNull(pending, "未轮到显示 pendingCover");
        ItemProvider firstFrame = definition.frame(0, 3, 0, null);

        assertNotNull(firstFrame);
        assertNotSame(pending, firstFrame);
        ItemProvider secondFrame = definition.frame(0, 3, 2, null);

        assertNotSame(firstFrame, secondFrame);
        assertNull(definition.frame(0, 3, 4, null), "先起播的槽走完即放行");
        assertSame(firstFrame, definition.frame(1, 5, 4, null), "后起播的槽迟到 4 tick 走到同一帧");
        assertSame(secondFrame, definition.frame(1, 5, 6, null));
        assertNull(definition.frame(1, 5, 8, null), "最后一槽走完恰逢整体到点");
    }

    @Test
    void loopWrapsAroundAndNeverEnds() {
        AnimationDefinition definition = AnimationDefinition.loop(new int[]{0}, 2, List.of(item(Material.RED_WOOL), item(Material.LIME_WOOL), item(Material.BLUE_WOOL)));

        assertTrue(definition.totalTicks() < 0, "循环动画无限时长");
        ItemProvider first = definition.frame(0, 0, 0, null);

        assertSame(first, definition.frame(0, 0, 6, null), "走满一轮回到第一帧");
        assertSame(definition.frame(0, 0, 2, null), definition.frame(0, 0, 128, null));
    }

    @Test
    void factoriesRejectIllegalArguments() {
        List<ItemStack> frames = List.of(item(Material.DIAMOND));

        assertThrows(IllegalArgumentException.class, () -> AnimationDefinition.frames(new int[]{0}, 0, frames));
        assertThrows(IllegalArgumentException.class, () -> AnimationDefinition.frames(new int[]{0}, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> AnimationDefinition.reveal(new int[]{0}, 0, null));
        assertThrows(IllegalArgumentException.class, () -> AnimationDefinition.staggeredFrames(new int[]{0}, -2, 2, frames, null));
        assertThrows(IllegalArgumentException.class, () -> AnimationDefinition.staggeredFrames(new int[]{0, 1}, 3, 2, frames, null), "间隔必须对齐周期");
        assertThrows(IllegalArgumentException.class, () -> AnimationDefinition.loop(new int[]{0}, 1, List.of()));
    }

    @Test
    void slotSequenceOverloadsMatchTheArrayForms() {
        SlotSequence sequence = SlotSequence.of(new PaneSize(3, 2), 4, 0, 2);
        List<ItemStack> frames = List.of(item(Material.DIAMOND));

        assertArrayEquals(new int[]{4, 0, 2}, AnimationDefinition.frames(sequence, 1, frames).slots());
        assertArrayEquals(new int[]{4, 0, 2}, AnimationDefinition.reveal(sequence, 2, null).slots());
        assertArrayEquals(new int[]{4, 0, 2}, AnimationDefinition.staggeredFrames(sequence, 2, 2, frames, null).slots());
        assertArrayEquals(new int[]{4, 0, 2}, AnimationDefinition.loop(sequence, 1, frames).slots());
    }

    private static ItemStack item(Material material) {
        return new ItemStack(material);
    }
}
