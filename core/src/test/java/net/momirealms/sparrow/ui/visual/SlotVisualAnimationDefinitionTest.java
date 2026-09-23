package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.time.TickingTestSupport;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle.FinishReason;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import net.momirealms.sparrow.ui.visual.animation.FrameFunction;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotVisualAnimationDefinitionTest {

    @BeforeEach
    void setUp() {
        TickingTestSupport.install();
    }

    @AfterEach
    void tearDown() {
        TickingTestSupport.restore();
    }

    @Test
    void playingAnimationCoversPerSlotAndGlobalLayersUntilCancelled() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 2);
        ItemProvider perSlot = marker();
        ItemProvider global = marker();
        visual.setVisualizerProvider(0, ignoredActual -> perSlot);
        visual.setVisualizerProvider(ignoredActual -> global, null);
        ItemProvider frame = marker();
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> frame));
        ResolvedVisual playing = visual.visualize(0, null);

        assertSame(frame, playing.provider());
        assertSame(handle, playing.sourceKey());
        assertNull(playing.placeholder());
        assertSame(global, visual.visualize(1, null).provider());
        handle.cancel();

        assertSame(perSlot, visual.visualize(0, null).provider());
    }

    @Test
    void zeroTickAnimationCompletesAtOnceWithoutCoveringOrScheduling() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 2);
        ItemProvider frame = marker();
        List<FinishReason> reasons = new ArrayList<>();
        visual.play(AnimationDefinition.of(new int[]{0}, 1, 0, (orderIndex, slot, elapsedTicks, actual) -> frame))
                .whenFinished(reasons::add);

        assertEquals(List.of(FinishReason.COMPLETED), reasons, "零时长播放出生即到点, 回调同步完成");
        assertNull(visual.visualize(0, null), "没有入场, 不盖住任何槽位");
        assertFalse(TickingTestSupport.scheduled(), "没有挂上时钟");
    }

    @Test
    void singleSlotRevealCompletesAtOnce() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        List<FinishReason> reasons = new ArrayList<>();
        visual.play(AnimationDefinition.reveal(new int[]{0}, 20, null)).whenFinished(reasons::add);

        assertEquals(List.of(FinishReason.COMPLETED), reasons);
        assertFalse(TickingTestSupport.scheduled());
    }

    @Test
    void newerAnimationCoversOlderAndYieldsWhereItsFrameIsNull() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 2);
        ItemProvider olderFrame = marker();
        ItemProvider newerFrame = marker();
        AtomicBoolean newerYields = new AtomicBoolean(false);
        AnimationHandle older = visual.play(AnimationDefinition.of(new int[]{0, 1}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> olderFrame));
        AnimationHandle newer = visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> newerYields.get() ? null : newerFrame));

        assertSame(newerFrame, visual.visualize(0, null).provider());
        assertSame(olderFrame, visual.visualize(1, null).provider());
        newerYields.set(true);

        assertSame(olderFrame, visual.visualize(0, null).provider());
        newer.cancel();
        older.cancel();

        assertNull(visual.visualize(0, null));
        assertNull(visual.visualize(1, null));
    }

    @Test
    void frameFunctionReceivesOrderIndexAndActual() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 3);
        ItemProvider frame = marker();
        AtomicInteger seenOrderIndex = new AtomicInteger(-1);
        AtomicReference<ItemStack> seenActual = new AtomicReference<>();
        visual.play(AnimationDefinition.of(new int[]{2, 0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> {
            seenOrderIndex.set(orderIndex);
            seenActual.set(actual);
            return frame;
        }));
        visual.visualize(2, null);

        assertEquals(0, seenOrderIndex.get());
        visual.visualize(0, null);

        assertEquals(1, seenOrderIndex.get());
        assertNull(seenActual.get());
    }

    @Test
    void playAndCancelDirtyExactlyTheCoveredSlots() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 3);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger bystander = new AtomicInteger();
        AtomicInteger last = new AtomicInteger();
        Subscription firstHandle = visual.attach(0, first::incrementAndGet);
        Subscription bystanderHandle = visual.attach(1, bystander::incrementAndGet);
        Subscription lastHandle = visual.attach(2, last::incrementAndGet);
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0, 2}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> null));

        assertEquals(1, first.get());
        assertEquals(0, bystander.get(), "未参与的槽位不该被标脏");
        assertEquals(1, last.get());
        handle.cancel();

        assertEquals(2, first.get());
        assertEquals(0, bystander.get());
        assertEquals(2, last.get());
        firstHandle.close();
        bystanderHandle.close();
        lastHandle.close();
    }

    @Test
    void configuredVisualizersStayReadableWhilePlaying() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        Function<ItemStack, ItemProvider> perSlot = ignoredActual -> null;
        Function<ItemStack, ItemProvider> global = ignoredActual -> null;
        visual.setVisualizerProvider(0, perSlot);
        visual.setVisualizerProvider(global, null);
        visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> marker()));

        assertSame(perSlot, visual.visualizerProvider(0));
        assertSame(global, visual.visualizerProvider());
    }

    @Test
    void animationCoversEmptySlotBackground() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        ItemProvider background = marker();
        visual.background(background);
        ItemProvider frame = marker();
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> frame));

        assertSame(frame, visual.visualizeWithBackground(0, null).provider());
        handle.cancel();

        assertSame(background, visual.visualizeWithBackground(0, null).provider());
    }

    @Test
    void playRejectsOutOfRangeAndDuplicateSlotsWithoutTrace() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 2);
        FrameFunction frame = (orderIndex, slot, elapsedTicks, actual) -> marker();

        assertThrows(IndexOutOfBoundsException.class, () -> visual.play(AnimationDefinition.of(new int[]{2}, 1, -1, frame)));
        assertThrows(IllegalArgumentException.class, () -> visual.play(AnimationDefinition.of(new int[]{0, 0}, 1, -1, frame)));
        assertThrows(IllegalArgumentException.class, () -> AnimationDefinition.of(new int[]{0}, 0, -1, frame));
        assertNull(visual.visualize(0, null));
        assertNull(visual.visualize(1, null));
    }

    @Test
    void emptyAnimationCompletesImmediately() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        AtomicInteger notified = new AtomicInteger();
        Subscription attachment = visual.attach(0, notified::incrementAndGet);
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[0], 1, 10, (orderIndex, slot, elapsedTicks, actual) -> null));
        List<FinishReason> reasons = new ArrayList<>();
        handle.whenFinished(reasons::add);
        handle.cancel();
        handle.whenFinished(reasons::add);

        assertEquals(List.of(FinishReason.COMPLETED, FinishReason.COMPLETED), reasons, "空播放注册即触发, 取消不改写原因");
        assertEquals(0, notified.get(), "空播放不标脏任何槽位");
        attachment.close();
    }

    @Test
    void cancelIsIdempotentAndEachRegistrationFiresOnce() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> marker()));
        List<FinishReason> first = new ArrayList<>();
        List<FinishReason> second = new ArrayList<>();
        handle.whenFinished(first::add);
        handle.whenFinished(second::add);
        handle.cancel();
        handle.cancel();

        assertEquals(List.of(FinishReason.CANCELLED), first);
        assertEquals(List.of(FinishReason.CANCELLED), second);
        List<FinishReason> late = new ArrayList<>();
        handle.whenFinished(late::add);

        assertEquals(List.of(FinishReason.CANCELLED), late);
    }

    @Test
    void failingFinishCallbackStillLetsTheRestFireAndSurfacesTheFailure() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> marker()));
        RuntimeException failure = new IllegalStateException("broken finish callback");
        List<FinishReason> surviving = new ArrayList<>();
        handle.whenFinished(ignoredReason -> {
            throw failure;
        });
        handle.whenFinished(surviving::add);

        assertSame(failure, assertThrows(RuntimeException.class, handle::cancel));
        assertEquals(List.of(FinishReason.CANCELLED), surviving);
        assertNull(visual.visualize(0, null), "抛异常也不该妨碍摘层");
    }

    @Test
    void concurrentPlayAndCancelResolveEveryPlayExactlyOnce() throws InterruptedException {
        int slots = 4;
        int threads = 8;
        int perThread = 200;
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), slots);
        ItemProvider frame = marker();
        List<AnimationHandle> surviving = new ArrayList<>();
        AtomicInteger finished = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int index = 0; index < threads; index++) {
            int slot = index % slots;
            Thread worker = new Thread(() -> {
                List<AnimationHandle> kept = new ArrayList<>();
                try {
                    start.await();
                    for (int round = 0; round < perThread; round++) {
                        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{slot}, 1, -1, (orderIndex, s, elapsedTicks, actual) -> frame));
                        handle.whenFinished(ignoredReason -> finished.incrementAndGet());
                        if (round % 2 == 0) {
                            handle.cancel();
                            handle.cancel();
                        } else {
                            kept.add(handle);
                        }
                    }
                    synchronized (surviving) {
                        surviving.addAll(kept);
                    }
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    done.countDown();
                }
            }, "slot-visual-animation-test-" + index);
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();

        assertTrue(done.await(30, TimeUnit.SECONDS), "并发播放超时");
        assertNull(failure.get(), "并发播放不该抛出异常");
        assertEquals(threads * perThread / 2, finished.get(), "当场取消的那一半各自恰好结束一次");
        for (int index = 0; index < surviving.size(); index++) {
            surviving.get(index).cancel();
        }

        assertEquals(threads * perThread, finished.get());
        for (int slot = 0; slot < slots; slot++) {
            assertNull(visual.visualize(slot, null), "全部结束后不该再有动画接管");
        }
    }

    @Test
    void heldHandleDoesNotPinTheHostVisual() {
        List<AnimationHandle> handles = new ArrayList<>();
        WeakReference<Object> probe = playAndDropVisual(handles);
        GcSupport.awaitCollected(probe);
        List<FinishReason> reasons = new ArrayList<>();
        handles.getFirst().whenFinished(reasons::add);
        handles.getFirst().cancel();

        assertEquals(List.of(FinishReason.CANCELLED), reasons);
    }

    @Test
    void clockDrivesPerSlotDirtyOnPeriodBoundaries() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 2);
        AtomicInteger covered = new AtomicInteger();
        AtomicInteger bystander = new AtomicInteger();
        Subscription coveredHandle = visual.attach(0, covered::incrementAndGet);
        Subscription bystanderHandle = visual.attach(1, bystander::incrementAndGet);
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0}, 2, -1, (orderIndex, slot, elapsedTicks, actual) -> marker()));

        assertEquals(1, covered.get(), "入场标脏一次");
        TickingTestSupport.advance(1);

        assertEquals(1, covered.get(), "不足一个周期不该推进");
        TickingTestSupport.advance(1);

        assertEquals(2, covered.get(), "满周期推进一帧");
        TickingTestSupport.advance(4);

        assertEquals(4, covered.get(), "每满一个周期恰好一次");
        assertEquals(0, bystander.get(), "未参与的槽位全程不受时钟惊动");
        handle.cancel();
        coveredHandle.close();
        bystanderHandle.close();
    }

    @Test
    void skippedTicksCollapseAndReplaysStaySameFrame() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        List<Long> seen = new ArrayList<>();
        ItemProvider frame = marker();
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> {
            seen.add(elapsedTicks);
            return frame;
        }));
        TickingTestSupport.advance(6);
        visual.visualize(0, null);
        visual.visualize(0, null);

        assertEquals(List.of(6L, 6L), seen);
        handle.cancel();
    }

    @Test
    void animationCompletesOnScheduleWithBoundedOvershoot() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        AtomicInteger dirties = new AtomicInteger();
        Subscription attachment = visual.attach(0, dirties::incrementAndGet);
        List<FinishReason> reasons = new ArrayList<>();
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0}, 2, 3, (orderIndex, slot, elapsedTicks, actual) -> marker()));
        handle.whenFinished(reasons::add);
        TickingTestSupport.advance(2);

        assertEquals(List.of(), reasons, "elapsed 2 未到总时长 3");
        TickingTestSupport.advance(2);

        assertEquals(List.of(FinishReason.COMPLETED), reasons);
        assertNull(visual.visualize(0, null), "播完摘层");
        assertEquals(3, dirties.get(), "入场 + 帧推进 + 摘层各标脏一次");
        assertFalse(TickingTestSupport.scheduled(), "最后一个订阅结束后时钟停摆");
        attachment.close();
    }

    @Test
    void zeroObserverTimelineStillCompletes() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        List<FinishReason> reasons = new ArrayList<>();
        visual.play(AnimationDefinition.of(new int[]{0}, 1, 2, (orderIndex, slot, elapsedTicks, actual) -> marker()))
                .whenFinished(reasons::add);
        TickingTestSupport.advance(2);

        assertEquals(List.of(FinishReason.COMPLETED), reasons);
    }

    @Test
    void animationStartAlignsToTheSharedPeriodBeat() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        AnimationHandle keepAlive = visual.play(AnimationDefinition.of(new int[]{0}, 1, -1,
                (orderIndex, slot, elapsedTicks, actual) -> null));
        TickingTestSupport.advance(1);
        List<Long> seen = new ArrayList<>();
        ItemProvider frame = marker();
        AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0}, 4, -1, (orderIndex, slot, elapsedTicks, actual) -> {
            seen.add(elapsedTicks);
            return frame;
        }));
        visual.visualize(0, null);

        assertEquals(List.of(1L), seen, "起播时刻回退到节拍上, 首帧从周期内的偏移处开始");
        TickingTestSupport.advance(3);
        seen.clear();
        visual.visualize(0, null);

        assertEquals(List.of(4L), seen, "对齐后节拍与帧边界重合, 一个周期整走完就换帧");
        handle.cancel();
        keepAlive.cancel();
    }

    @Test
    void failingFinishCallbackStillFinishesTheRemainingAnimations() {
        WindowVisualImpl visual = new WindowVisualImpl(new Bindings(), 2);
        RuntimeException failure = new IllegalStateException("broken finish callback");
        List<FinishReason> surviving = new ArrayList<>();
        visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> marker()))
                .whenFinished(ignoredReason -> {
                    throw failure;
                });
        visual.play(AnimationDefinition.of(new int[]{1}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> marker()))
                .whenFinished(surviving::add);

        assertSame(failure, assertThrows(RuntimeException.class, () -> visual.finishAnimations(FinishReason.WINDOW_CLOSED)));
        assertEquals(List.of(FinishReason.WINDOW_CLOSED), surviving);
        assertNull(visual.visualize(1, null), "后一个动画也必须被摘层");
        assertFalse(TickingTestSupport.scheduled(), "全部终结后时钟解绑停摆");
    }

    @Test
    void failedClockSubscriptionLeavesNoAnimationBehind() {
        RuntimeException failure = new IllegalStateException("scheduler unavailable");
        TickingTestSupport.installFailing(failure);
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);

        assertSame(failure, assertThrows(RuntimeException.class, () -> visual.play(
                AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> marker()))));

        assertNull(visual.visualize(0, null), "挂钟失败不得留下盖着槽位的播放");
    }

    @Test
    void windowClosedFinishesEveryPlayingAnimationOnce() {
        WindowVisualImpl visual = new WindowVisualImpl(new Bindings(), 2);
        List<FinishReason> first = new ArrayList<>();
        List<FinishReason> second = new ArrayList<>();
        visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> marker())).whenFinished(first::add);
        visual.play(AnimationDefinition.of(new int[]{1}, 1, 20, (orderIndex, slot, elapsedTicks, actual) -> marker())).whenFinished(second::add);
        visual.finishAnimations(FinishReason.WINDOW_CLOSED);
        visual.finishAnimations(FinishReason.WINDOW_CLOSED);

        assertEquals(List.of(FinishReason.WINDOW_CLOSED), first);
        assertEquals(List.of(FinishReason.WINDOW_CLOSED), second);
        assertNull(visual.visualize(0, null));
        assertNull(visual.visualize(1, null));
        assertFalse(TickingTestSupport.scheduled(), "终结后时钟解绑停摆");
    }

    @Test
    void concurrentCancelAndCompletionResolveExactlyOnce() throws InterruptedException {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        for (int round = 0; round < 200; round++) {
            AnimationHandle handle = visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> marker()));
            List<FinishReason> fired = Collections.synchronizedList(new ArrayList<>());
            handle.whenFinished(fired::add);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            Thread canceller = new Thread(() -> {
                try {
                    start.await();
                    handle.cancel();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "animation-finish-race-cancel-" + round);
            Thread completer = new Thread(() -> {
                try {
                    start.await();
                    ((ActiveSlotAnimation) handle).finish(FinishReason.COMPLETED);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "animation-finish-race-complete-" + round);
            canceller.setDaemon(true);
            completer.setDaemon(true);
            canceller.start();
            completer.start();
            start.countDown();

            assertTrue(done.await(10, TimeUnit.SECONDS), "终结竞争超时");
            assertEquals(1, fired.size(), "取消与自然完成竞争也恰好结束一次");
            List<FinishReason> late = new ArrayList<>();
            handle.whenFinished(late::add);

            assertEquals(fired, late, "输掉竞争的终结不得改写已落定的原因");
        }
    }

    @Test
    void collectedHostStopsTheClock() {
        List<AnimationHandle> handles = new ArrayList<>();
        WeakReference<Object> probe = playAndDropVisual(handles);

        assertTrue(TickingTestSupport.scheduled(), "播放中时钟在走");
        GcSupport.awaitCollected(probe);
        TickingTestSupport.advance(1);

        assertFalse(TickingTestSupport.scheduled(), "宿主回收后时钟停摆");
    }

    @Test
    void elapsedStaysExactAcrossFrozenClockGaps() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        List<FinishReason> reasons = new ArrayList<>();
        visual.play(AnimationDefinition.of(new int[]{0}, 1, 2, (orderIndex, slot, elapsedTicks, actual) -> marker()))
                .whenFinished(reasons::add);
        TickingTestSupport.advance(2);

        assertEquals(List.of(FinishReason.COMPLETED), reasons);
        assertFalse(TickingTestSupport.scheduled(), "第一次播放结束后停表");
        TickingTestSupport.advance(3);
        List<Long> seen = new ArrayList<>();
        ItemProvider frame = marker();
        AnimationHandle second = visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> {
            seen.add(elapsedTicks);
            return frame;
        }));
        visual.visualize(0, null);
        TickingTestSupport.advance(1);
        visual.visualize(0, null);

        assertEquals(List.of(0L, 1L), seen, "第二次播放的 elapsed 从 0 起算, 不受停表间隙污染");
        second.cancel();
    }

    private static WeakReference<Object> playAndDropVisual(List<AnimationHandle> handles) {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        ItemProvider frame = marker();
        handles.add(visual.play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> frame)));
        return new WeakReference<>(visual);
    }

    private static ItemProvider marker() {
        return ignoredContext -> {
            throw new AssertionError("标记 provider 只用来比对身份, 不该被求值");
        };
    }
}
