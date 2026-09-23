package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.state.internal.time.TickingTestSupport;
import net.momirealms.sparrow.ui.window.RenderCell;
import net.momirealms.sparrow.ui.window.SparrowUiTestRuntime;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemBuilderPerRenderAsyncTest {

    private static final int SLOT = 4;
    private static final int OTHER_SLOT = 7;
    private ServerMock server;
    private DirtyTrackingWindow window;
    private RenderContext context;
    private final List<Throwable> reported = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentMap<RenderContext, RenderCell> renderCells = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        Player viewer = this.server.addPlayer();
        SparrowUiTestRuntime.installPlugin();
        SparrowUiTestRuntime.installOwnership(() -> true);
        SparrowUI.getInstance().setExceptionHandler((ignoredMessage, throwable) -> this.reported.add(throwable));
        this.window = new DirtyTrackingWindow(viewer);
        this.context = new RenderContext(this.window, SLOT);
    }

    @AfterEach
    void tearDown() {
        for (RenderCell renderCell : this.renderCells.values()) {
            renderCell.close();
        }
        this.renderCells.clear();
        SparrowUI.getInstance().setExceptionHandler((ignoredMessage, ignoredThrowable) -> { });
        SparrowUiTestRuntime.restoreOwnership();
        SparrowUiTestRuntime.restorePlugin();
        MockBukkit.unmock();
    }

    @Test
    void firstRenderShowsPlaceholderAndSubmitsExactlyOnce() {
        ItemStack placeholder = new ItemStack(Material.PAPER);
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider, placeholder).build();

        assertEquals(placeholder, render(item, this.context));
        assertEquals(1, provider.submissionCount());
        render(item, this.context);
        render(item, this.context);

        assertEquals(1, provider.submissionCount());
    }

    @Test
    void completionInvalidatesOwnSlotWithoutTriggeringResubmission() {
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();
        ItemStack computed = new ItemStack(Material.DIAMOND);
        render(item, this.context);
        provider.completeLast(computed);

        assertEquals(List.of(SLOT), this.window.dirtySlots());
        ItemStack afterCompletion = render(item, this.context);

        assertEquals(computed, afterCompletion);
        assertSame(afterCompletion, render(item, this.context));
        assertEquals(1, provider.submissionCount());
        this.cellFor(this.context).dirty();

        assertSame(afterCompletion, render(item, this.context));
        assertEquals(2, provider.submissionCount());
    }

    @Test
    void invalidationWhileRequestIsInFlightRunsOneTrailingRequest() {
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();
        ItemStack first = new ItemStack(Material.DIAMOND);
        ItemStack second = new ItemStack(Material.EMERALD);
        render(item, this.context);
        RenderCell renderCell = this.cellFor(this.context);
        renderCell.dirty();
        renderCell.dirty();
        provider.completeLast(first);
        this.window.clearDirtySlots();

        assertSame(first, render(item, this.context));
        assertEquals(2, provider.submissionCount(), "在飞期间的多次失效只补跑一轮");
        provider.completeLast(second);
        this.window.clearDirtySlots();

        assertSame(second, render(item, this.context));
        assertEquals(2, provider.submissionCount());
    }

    @Test
    void failedInFlightRequestPreservesTrailingInvalidation() {
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();
        IllegalStateException failure = new IllegalStateException("compute failed");
        render(item, this.context);
        this.cellFor(this.context).dirty();
        provider.failLast(failure);

        assertEquals(List.of(failure), this.reported);
        assertEquals(List.of(SLOT), this.window.dirtySlots());
        this.window.clearDirtySlots();
        render(item, this.context);

        assertEquals(2, provider.submissionCount());
    }

    @Test
    void closingRenderStateRejectsLateCompletion() {
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();
        render(item, this.context);
        this.cellFor(this.context).close();
        provider.completeLast(new ItemStack(Material.DIAMOND));

        assertTrue(this.window.dirtySlots().isEmpty());
    }

    @Test
    void batchProjectionDoesNotRefreshSiblingFutureOnCompletion() {
        ManualAsyncProvider firstProvider = new ManualAsyncProvider();
        ManualAsyncProvider secondProvider = new ManualAsyncProvider();
        AtomicInteger invalidations = new AtomicInteger();
        RenderCell firstCell = new RenderCell(this.context, invalidations::incrementAndGet, this.reported::add);
        RenderCell secondCell = new RenderCell(
                new RenderContext(this.window, OTHER_SLOT),
                invalidations::incrementAndGet,
                this.reported::add
        );
        RenderCell.Intent firstIntent = new RenderCell.Intent.Projected(firstProvider, null, ItemStack.empty());
        RenderCell.Intent secondIntent = new RenderCell.Intent.Projected(secondProvider, null, ItemStack.empty());
        firstCell.render(firstIntent);
        secondCell.render(secondIntent);
        firstProvider.completeLast(new ItemStack(Material.DIAMOND));
        firstCell.render(firstIntent);
        secondCell.render(secondIntent);

        assertEquals(1, firstProvider.submissionCount());
        assertEquals(1, secondProvider.submissionCount());
        secondProvider.completeLast(new ItemStack(Material.EMERALD));
        firstCell.render(firstIntent);
        secondCell.render(secondIntent);

        assertEquals(2, invalidations.get());
        assertEquals(1, firstProvider.submissionCount());
        assertEquals(1, secondProvider.submissionCount());
        firstCell.close();
        secondCell.close();
    }

    @Test
    void synchronouslyCompletedStageSkipsThePlaceholderEntirely() {
        ItemStack computed = new ItemStack(Material.DIAMOND);
        Item item = Item.builder()
                .setItemProviderAsync(
                        ignoredContext -> CompletableFuture.completedFuture(computed),
                        new ItemStack(Material.PAPER)
                )
                .build();

        assertEquals(computed, render(item, this.context));
    }

    @Test
    void failedCompletionKeepsCurrentResultReportsOnceAndWaitsForInvalidation() {
        ItemStack placeholder = new ItemStack(Material.PAPER);
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider, placeholder).build();
        IllegalStateException failure = new IllegalStateException("compute failed");
        render(item, this.context);
        provider.failLast(failure);

        assertEquals(List.of(failure), this.reported);
        assertTrue(this.window.dirtySlots().isEmpty());
        assertEquals(placeholder, render(item, this.context));
        assertEquals(1, provider.submissionCount());
        this.cellFor(this.context).dirty();

        assertEquals(placeholder, render(item, this.context));
        assertEquals(2, provider.submissionCount());
    }

    @Test
    void synchronouslyThrowingProviderIsReportedAndLeavesTheSlotSubmittable() {
        ItemStack placeholder = new ItemStack(Material.PAPER);
        IllegalStateException failure = new IllegalStateException("provide failed");
        AtomicInteger calls = new AtomicInteger();
        Item item = Item.builder()
                .setItemProviderAsync(ignoredContext -> {
                    calls.incrementAndGet();
                    throw failure;
                }, placeholder)
                .build();

        assertEquals(placeholder, render(item, this.context));
        assertEquals(1, calls.get());
        assertEquals(List.of(failure), this.reported);
        this.cellFor(this.context).dirty();

        assertEquals(placeholder, render(item, this.context));
        assertEquals(2, calls.get());
    }

    @Test
    void nullCompletionIsReportedAsAContractFailure() {
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();
        render(item, this.context);
        provider.completeLast(null);

        assertEquals(1, this.reported.size());
        assertTrue(this.reported.getFirst() instanceof NullPointerException);
        assertTrue(this.window.dirtySlots().isEmpty());
        assertTrue(render(item, this.context).isEmpty());
        assertEquals(1, provider.submissionCount());
        this.cellFor(this.context).dirty();
        render(item, this.context);

        assertEquals(2, provider.submissionCount());
    }

    @Test
    void completionsDoNotBounceBetweenSiblingSlots() {
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();
        RenderContext other = new RenderContext(this.window, OTHER_SLOT);
        ItemAttachment first = AttachSupport.attach(item, ignoredItem -> this.window.notifyUpdate(SLOT));
        ItemAttachment second = AttachSupport.attach(item, ignoredItem -> this.window.notifyUpdate(OTHER_SLOT));
        render(item, this.context);
        render(item, other);
        provider.complete(0, new ItemStack(Material.DIAMOND));
        provider.complete(1, new ItemStack(Material.DIAMOND));
        this.window.clearDirtySlots();
        render(item, this.context);
        render(item, other);

        assertTrue(this.window.dirtySlots().isEmpty());
        assertEquals(2, provider.submissionCount());
        this.cellFor(this.context).dirty();
        render(item, this.context);
        provider.completeLast(new ItemStack(Material.EMERALD));

        assertEquals(List.of(SLOT), this.window.dirtySlots());
        this.window.clearDirtySlots();
        render(item, this.context);

        assertTrue(this.window.dirtySlots().isEmpty(), "兄弟槽位仍在互相标脏");
        assertEquals(3, provider.submissionCount());
        first.close();
        second.close();
    }

    @Test
    void completingOneSlotLeavesSiblingSlotsUntouched() {
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();
        RenderContext other = new RenderContext(this.window, OTHER_SLOT);
        ItemAttachment first = AttachSupport.attach(item, ignoredItem -> this.window.notifyUpdate(SLOT));
        ItemAttachment second = AttachSupport.attach(item, ignoredItem -> this.window.notifyUpdate(OTHER_SLOT));
        render(item, this.context);
        render(item, other);
        provider.complete(0, new ItemStack(Material.DIAMOND));

        assertEquals(List.of(SLOT), this.window.dirtySlots());
        assertEquals(2, provider.submissionCount());
        render(item, other);

        assertEquals(2, provider.submissionCount());
        first.close();
        second.close();
    }

    @Test
    void eachSlotKeepsItsOwnResult() {
        ItemStack placeholder = new ItemStack(Material.PAPER);
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider, placeholder).build();
        RenderContext other = new RenderContext(this.window, OTHER_SLOT);
        ItemStack computed = new ItemStack(Material.DIAMOND);
        render(item, this.context);
        render(item, other);
        provider.complete(0, computed);

        assertEquals(computed, render(item, this.context));
        assertEquals(placeholder, render(item, other));
    }

    @Test
    void switchingSourcesAbandonsThePreviousResult() {
        ManualAsyncProvider firstProvider = new ManualAsyncProvider();
        ManualAsyncProvider secondProvider = new ManualAsyncProvider();
        Item first = Item.builder().setItemProviderAsync(firstProvider).build();
        Item second = Item.builder().setItemProviderAsync(secondProvider).build();
        ItemStack firstComputed = new ItemStack(Material.DIAMOND);
        render(first, this.context);
        firstProvider.completeLast(firstComputed);

        assertEquals(firstComputed, render(first, this.context));
        render(second, this.context);

        assertTrue(render(first, this.context).isEmpty());
        assertEquals(2, firstProvider.submissionCount());
    }

    @Test
    void computedItemOwnershipTransfersWithoutAnotherCopy() {
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();
        ItemStack computed = new ItemStack(Material.DIAMOND, 3);
        render(item, this.context);
        provider.completeLast(computed);

        assertSame(computed, render(item, this.context));
    }

    @Test
    void failingSlotInvalidationIsReportedAndKeepsTheComputedResult() {
        IllegalStateException failure = new IllegalStateException("dirty failed");
        DirtyTrackingWindow failingWindow = new DirtyTrackingWindow(this.server.addPlayer(), failure);
        RenderContext failingContext = new RenderContext(failingWindow, SLOT);
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();
        ItemStack computed = new ItemStack(Material.DIAMOND);
        render(item, failingContext);
        provider.completeLast(computed);

        assertEquals(List.of(failure), this.reported);
        assertEquals(computed, render(item, failingContext));
    }

    @Test
    void concurrentRendersOnDifferentSlotsStayIsolated() throws Exception {
        int rounds = 500;
        Item item = Item.builder()
                .setItemProviderAsync(context -> CompletableFuture.completedFuture(
                        new ItemStack(Material.DIAMOND, context.windowSlot)
                ))
                .build();
        RenderContext first = new RenderContext(this.window, 1);
        RenderContext second = new RenderContext(this.window, 2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstTask = executor.submit(() -> renderRepeatedly(item, first, rounds));
            Future<?> secondTask = executor.submit(() -> renderRepeatedly(item, second, rounds));
            firstTask.get(10, TimeUnit.SECONDS);
            secondTask.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(this.reported.isEmpty());
    }

    @Test
    void periodicRefreshComesFromTheBuilderNotTheAsyncSource() {
        TickingTestSupport.install();
        try {
            ManualAsyncProvider provider = new ManualAsyncProvider();
            Item item = Item.builder().setItemProviderAsync(provider).updatePeriodically(5).build();
            AtomicInteger invalidations = new AtomicInteger();
            ItemAttachment attachment = AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());
            ItemStack computed = new ItemStack(Material.DIAMOND);
            TickingTestSupport.advance(4);

            assertEquals(0, invalidations.get());
            TickingTestSupport.advance(1);

            assertEquals(1, invalidations.get());
            render(item, this.context);
            provider.completeLast(computed);
            render(item, this.context);

            assertEquals(1, provider.submissionCount());
            this.cellFor(this.context).dirty();
            render(item, this.context);

            assertEquals(2, provider.submissionCount());
            attachment.close();
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void periodicDependencyStopsWhenTheItemIsDetached() {
        TickingTestSupport.install();
        try {
            Item item = Item.builder().setItemProviderAsync(new ManualAsyncProvider()).updatePeriodically(5).build();
            AtomicInteger invalidations = new AtomicInteger();
            ItemAttachment attachment = AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());

            assertTrue(TickingTestSupport.scheduled(), "有挂载时调度任务应当启动");
            attachment.close();
            TickingTestSupport.advance(10);

            assertEquals(0, invalidations.get());
            assertFalse(TickingTestSupport.scheduled(), "没有挂载时调度任务应当停摆");
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void defaultPlaceholderRendersAnEmptyItem() {
        ManualAsyncProvider provider = new ManualAsyncProvider();
        Item item = Item.builder().setItemProviderAsync(provider).build();

        assertTrue(render(item, this.context).isEmpty());
    }

    private ItemStack render(Item item, RenderContext context) {
        return this.cellFor(context).render(
                new RenderCell.Intent.Projected(item.getItemProvider(), item.getPlaceholder(), ItemStack.empty())
        );
    }

    private RenderCell cellFor(RenderContext context) {
        return this.renderCells.computeIfAbsent(
                context,
                current -> new RenderCell(
                        current,
                        () -> current.window.notifyUpdate(current.windowSlot),
                        this.reported::add
                )
        );
    }

    private void renderRepeatedly(Item item, RenderContext context, int rounds) {
        for (int round = 0; round < rounds; round++) {
            ItemStack rendered = render(item, context);

            assertEquals(context.windowSlot, rendered.getAmount());
        }
    }

    private static final class ManualAsyncProvider implements ItemProvider {
        private final List<CompletableFuture<ItemStack>> submissions = Collections.synchronizedList(new ArrayList<>());
        @Override
        public @NonNull CompletableFuture<ItemStack> provide(@NonNull RenderContext context) {
            CompletableFuture<ItemStack> submission = new CompletableFuture<>();
            this.submissions.add(submission);
            return submission;
        }
        private int submissionCount() {
            return this.submissions.size();
        }
        private void complete(int index, ItemStack item) {
            this.submissions.get(index).complete(item);
        }
        private void completeLast(ItemStack item) {
            this.submissions.getLast().complete(item);
        }
        private void failLast(Throwable throwable) {
            this.submissions.getLast().completeExceptionally(throwable);
        }
    }

    private static final class DirtyTrackingWindow extends WindowStub {
        private final List<Integer> dirtySlots = Collections.synchronizedList(new ArrayList<>());
        private final RuntimeException dirtyFailure;
        private DirtyTrackingWindow(Player viewer) {
            this(viewer, null);
        }
        private DirtyTrackingWindow(Player viewer, RuntimeException dirtyFailure) {
            super(viewer);
            this.dirtyFailure = dirtyFailure;
        }
        @Override
        public void notifyUpdate(int windowSlot) {
            this.dirtySlots.add(windowSlot);
            if (this.dirtyFailure != null) {
                throw this.dirtyFailure;
            }
        }
        private List<Integer> dirtySlots() {
            return List.copyOf(this.dirtySlots);
        }
        private void clearDirtySlots() {
            this.dirtySlots.clear();
        }
    }
}
