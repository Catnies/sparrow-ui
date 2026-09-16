package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.WindowStub;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderCellTest {

    private ServerMock server;
    private RenderCell cell;
    private RenderContext context;
    private final AtomicInteger invalidations = new AtomicInteger();
    private final List<Throwable> reported = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        Player viewer = this.server.addPlayer();
        SparrowUiTestRuntime.installPlugin();
        SparrowUiTestRuntime.installOwnership(() -> true);
        SparrowUI.getInstance().setExceptionHandler((ignoredMessage, throwable) -> this.reported.add(throwable));
        this.context = new RenderContext(new WindowStub(viewer), 4);
        this.cell = new RenderCell(this.context, this.invalidations::incrementAndGet, this.reported::add);
    }

    @AfterEach
    void tearDown() {
        this.cell.close();
        SparrowUI.getInstance().setExceptionHandler((ignoredMessage, ignoredThrowable) -> { });
        SparrowUiTestRuntime.restoreOwnership();
        SparrowUiTestRuntime.restorePlugin();
        MockBukkit.unmock();
    }

    @Test
    void immediateProviderRendersDirectlyWithoutProjectionState() {
        AtomicInteger renders = new AtomicInteger();
        ItemStack rendered = new ItemStack(Material.DIAMOND);
        ItemProvider provider = ItemProvider.sync(ignoredContext -> {
            renders.incrementAndGet();
            return rendered;
        });

        assertSame(rendered, this.cell.render(projected(provider)));
        assertSame(rendered, this.cell.render(projected(provider)));
        assertEquals(2, renders.get());
        assertEquals(0, this.invalidations.get());
    }

    @Test
    void immediateProviderReturningNullIsRejected() {
        ImmediateItemProvider provider = ignoredContext -> null;
        ItemStack lastResort = new ItemStack(Material.PAPER);
        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> this.cell.render(new RenderCell.Intent.Projected(provider, null, lastResort))
        );

        assertEquals("rendered item", failure.getMessage());
    }

    @Test
    void firstRenderSubmitsOnceAndShowsPlaceholderWhileInFlight() {
        ManualProvider provider = new ManualProvider();
        ItemStack placeholder = new ItemStack(Material.PAPER);
        RenderCell.Intent intent = projected(provider, ItemProvider.constant(placeholder));

        assertEquals(placeholder, this.cell.render(intent));
        this.cell.render(intent);
        this.cell.render(intent);

        assertEquals(1, provider.submissionCount());
    }

    @Test
    void completionPublishesValueThenNotifiesWithoutResubmitting() {
        ManualProvider provider = new ManualProvider();
        RenderCell.Intent intent = projected(provider);
        ItemStack computed = new ItemStack(Material.DIAMOND);
        this.cell.render(intent);
        provider.completeLast(computed);

        assertEquals(1, this.invalidations.get());
        assertSame(computed, this.cell.render(intent));
        assertSame(computed, this.cell.render(intent));
        assertSame(computed, this.cell.render(intent));
        assertEquals(1, provider.submissionCount());
        this.cell.dirty();

        assertSame(computed, this.cell.render(intent));
        assertEquals(2, provider.submissionCount());
    }

    @Test
    void dirtyWhileInFlightKeepsRegistrationAndRunsOneTrailingRequest() {
        ManualProvider provider = new ManualProvider();
        RenderCell.Intent intent = projected(provider);
        ItemStack first = new ItemStack(Material.DIAMOND);
        ItemStack second = new ItemStack(Material.EMERALD);
        this.cell.render(intent);
        this.cell.dirty();
        this.cell.dirty();
        this.cell.render(intent);
        provider.completeLast(first);

        assertEquals(1, this.invalidations.get());
        assertSame(first, this.cell.render(intent));
        assertEquals(2, provider.submissionCount(), "在飞期间的多次失效只补跑一轮");
        provider.completeLast(second);

        assertSame(second, this.cell.render(intent));
        assertEquals(2, provider.submissionCount());
    }

    @Test
    void sourceSwitchAbandonsInFlightTaskAndOldValue() {
        ManualProvider first = new ManualProvider();
        ManualProvider second = new ManualProvider();
        ItemStack firstValue = new ItemStack(Material.DIAMOND);
        ItemStack placeholder = new ItemStack(Material.PAPER);
        this.cell.render(projected(first));
        first.completeLast(firstValue);

        assertSame(firstValue, this.cell.render(projected(first)));
        assertEquals(placeholder, this.cell.render(projected(second, ItemProvider.constant(placeholder))));
        assertEquals(1, second.submissionCount());
        this.cell.render(projected(first));

        assertEquals(2, first.submissionCount());
    }

    @Test
    void lateCompletionOfAbandonedTaskIsDroppedSilently() {
        ManualProvider abandoned = new ManualProvider();
        ManualProvider active = new ManualProvider();
        this.cell.render(projected(abandoned));
        this.cell.render(projected(active));
        this.invalidations.set(0);
        abandoned.completeLast(new ItemStack(Material.DIAMOND));

        assertEquals(0, this.invalidations.get());
        ItemStack visible = this.cell.render(projected(active, ItemProvider.constant(new ItemStack(Material.PAPER))));

        assertEquals(Material.PAPER, visible.getType());
    }

    @Test
    void directIntentRetiresProjectionAndDropsLateCompletion() {
        ManualProvider provider = new ManualProvider();
        ItemStack actual = new ItemStack(Material.STONE, 3);
        this.cell.render(projected(provider));

        assertSame(actual, this.cell.render(new RenderCell.Intent.Direct(actual)));
        this.invalidations.set(0);
        provider.completeLast(new ItemStack(Material.DIAMOND));

        assertEquals(0, this.invalidations.get());
        assertSame(actual, this.cell.render(new RenderCell.Intent.Direct(actual)));
    }

    @Test
    void synchronouslyCompletedFutureShowsTruthOnFirstFrameWithoutNotifying() {
        ItemStack computed = new ItemStack(Material.DIAMOND);
        AtomicInteger placeholderRenders = new AtomicInteger();
        ItemProvider provider = ignoredContext -> CompletableFuture.completedFuture(computed);
        ImmediateItemProvider placeholder = ItemProvider.sync(ignoredContext -> {
            placeholderRenders.incrementAndGet();
            return new ItemStack(Material.PAPER);
        });

        assertSame(computed, this.cell.render(projected(provider, placeholder)));
        assertEquals(0, placeholderRenders.get());
        assertEquals(0, this.invalidations.get());
    }

    @Test
    void failedCompletionKeepsOldValueAndWaitsForNextInvalidation() {
        ManualProvider provider = new ManualProvider();
        RenderCell.Intent intent = projected(provider);
        ItemStack first = new ItemStack(Material.DIAMOND);
        IllegalStateException failure = new IllegalStateException("compute failed");
        this.cell.render(intent);
        provider.completeLast(first);
        this.cell.render(intent);
        this.cell.dirty();

        assertSame(first, this.cell.render(intent));
        provider.failLast(failure);

        assertEquals(List.of(failure), this.reported);
        assertSame(first, this.cell.render(intent));
        assertEquals(2, provider.submissionCount());
        this.cell.dirty();

        assertSame(first, this.cell.render(intent));
        assertEquals(3, provider.submissionCount());
    }

    @Test
    void failureWithPendingInvalidationNotifiesForTrailingRequest() {
        ManualProvider provider = new ManualProvider();
        RenderCell.Intent intent = projected(provider);
        this.cell.render(intent);
        this.cell.dirty();
        this.invalidations.set(0);
        provider.failLast(new IllegalStateException("compute failed"));

        assertEquals(1, this.invalidations.get());
        this.cell.render(intent);

        assertEquals(2, provider.submissionCount());
    }

    @Test
    void providerThrowingSynchronouslyIsReportedAndStaysSubmittable() {
        IllegalStateException failure = new IllegalStateException("provide failed");
        AtomicInteger calls = new AtomicInteger();
        ItemProvider provider = ignoredContext -> {
            calls.incrementAndGet();
            throw failure;
        };
        ItemStack lastResort = new ItemStack(Material.PAPER);

        assertEquals(lastResort, this.cell.render(new RenderCell.Intent.Projected(provider, null, lastResort)));
        assertEquals(List.of(failure), this.reported);
        this.cell.dirty();

        assertEquals(lastResort, this.cell.render(new RenderCell.Intent.Projected(provider, null, lastResort)));
        assertEquals(2, calls.get());
    }

    @Test
    void missingPlaceholderFallsBackToLastResort() {
        ManualProvider provider = new ManualProvider();
        ItemStack lastResort = new ItemStack(Material.BARRIER);

        assertSame(lastResort, this.cell.render(new RenderCell.Intent.Projected(provider, null, lastResort)));
        this.cell.dirty();
        ItemStack ready = new ItemStack(Material.PAPER);

        assertSame(ready, this.cell.render(projected(provider, ItemProvider.sync(ignoredContext -> ready))));
    }

    @Test
    void resetForcesRecomputeAndDropsLateCompletion() {
        ManualProvider provider = new ManualProvider();
        RenderCell.Intent intent = projected(provider);
        ItemStack stale = new ItemStack(Material.DIAMOND);
        this.cell.render(intent);
        this.cell.reset();
        this.invalidations.set(0);
        provider.completeLast(stale);

        assertEquals(0, this.invalidations.get(), "重开前的迟到完成不再通知");
        ItemStack placeholder = new ItemStack(Material.PAPER);

        assertEquals(placeholder, this.cell.render(projected(provider, ItemProvider.constant(placeholder))));
        assertEquals(2, provider.submissionCount());
    }

    @Test
    void resetFromAnotherThreadAbandonsTheTaskAndRecomputesOnTheNextRender() throws InterruptedException {
        ManualProvider provider = new ManualProvider();
        ItemStack stale = new ItemStack(Material.DIAMOND);
        this.cell.render(projected(provider));
        this.invalidations.set(0);
        Thread committer = new Thread(this.cell::reset, "commit-thread");
        committer.start();
        committer.join();
        provider.completeLast(stale);

        assertEquals(0, this.invalidations.get(), "作废之后的迟到完成不再通知");
        ItemStack placeholder = new ItemStack(Material.PAPER);

        assertEquals(placeholder, this.cell.render(projected(provider, ItemProvider.constant(placeholder))));
        assertEquals(2, provider.submissionCount(), "别的线程提交的重算要求不会丢失");
    }

    @Test
    void closedCellDropsLateCompletionSilently() {
        ManualProvider provider = new ManualProvider();
        this.cell.render(projected(provider));
        this.cell.close();
        this.invalidations.set(0);
        provider.completeLast(new ItemStack(Material.DIAMOND));

        assertEquals(0, this.invalidations.get());
        assertTrue(this.reported.isEmpty());
    }

    @Test
    void stableSourceKeyToleratesAFreshProviderEveryRender() {
        ManualProvider computation = new ManualProvider();
        Object sourceKey = new Object();
        Supplier<RenderCell.Intent> freshIntent = () -> new RenderCell.Intent.Projected(
                sourceKey,
                ignoredContext -> computation.provide(ignoredContext),
                null,
                ItemStack.empty()
        );
        this.cell.render(freshIntent.get());
        this.cell.render(freshIntent.get());
        this.cell.render(freshIntent.get());

        assertEquals(1, computation.submissionCount(), "来源身份没变就不该重新提交");
        ItemStack computed = new ItemStack(Material.DIAMOND);
        computation.completeLast(computed);

        assertSame(computed, this.cell.render(freshIntent.get()));
        assertEquals(1, computation.submissionCount());
    }

    @Test
    void changedSourceKeyAbandonsTheResultEvenWhenProviderIsIdentical() {
        ManualProvider provider = new ManualProvider();
        ItemStack computed = new ItemStack(Material.DIAMOND);
        ItemStack placeholder = new ItemStack(Material.PAPER);
        this.cell.render(new RenderCell.Intent.Projected(new Object(), provider, null, ItemStack.empty()));
        provider.completeLast(computed);
        ItemStack rendered = this.cell.render(new RenderCell.Intent.Projected(
                new Object(), provider, ItemProvider.constant(placeholder), ItemStack.empty()));

        assertEquals(placeholder, rendered);
        assertEquals(2, provider.submissionCount());
    }

    private static RenderCell.Intent projected(ItemProvider provider) {
        return new RenderCell.Intent.Projected(provider, null, ItemStack.empty());
    }

    private static RenderCell.Intent projected(ItemProvider provider, ImmediateItemProvider placeholder) {
        return new RenderCell.Intent.Projected(provider, placeholder, ItemStack.empty());
    }

    private static final class ManualProvider implements ItemProvider {
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
        private void completeLast(ItemStack item) {
            this.submissions.getLast().complete(item);
        }
        private void failLast(Throwable throwable) {
            this.submissions.getLast().completeExceptionally(throwable);
        }
    }
}
