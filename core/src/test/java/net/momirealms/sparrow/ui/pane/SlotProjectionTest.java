package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.state.GcSupport;
import net.momirealms.sparrow.ui.state.ManualExecutor;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.SparrowUiTestRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotProjectionTest {

    private VirtualInventory vault;
    private Consumer<? super String> previousWarningHandler;
    private boolean previousWarningsEnabled;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.previousWarningHandler = SparrowUI.getInstance().warningHandler();
        this.previousWarningsEnabled = SparrowUI.getInstance().warningsEnabled();
        this.vault = new VirtualInventory(9);
    }

    @AfterEach
    void tearDown() {
        SparrowUI.getInstance().setWarningHandler(this.previousWarningHandler);
        SparrowUI.getInstance().warningsEnabled(this.previousWarningsEnabled);
        MockBukkit.unmock();
    }

    @Test
    void attachWritesTheSequenceRightAway() {
        NormalPane pane = Pane.empty(3, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0, 1, 2));
        pane.project(SlotSequence.all(pane.size()), source, this::link, Runnable::run);

        assertEquals(this.link(0), pane.element(0));
        assertEquals(this.link(1), pane.element(1));
        assertEquals(this.link(2), pane.element(2));
    }

    @Test
    void defaultProjectionUsesTheSharedExecutorOnlyAfterTheInitialEvaluation() {
        SparrowUiTestRuntime.installPlugin();
        try {
            NormalPane pane = Pane.empty(3, 1);
            MutableSignal<List<Integer>> source = Signal.of(List.of(0));
            int before = SparrowUiTestRuntime.asyncRuns();
            SlotProjection projection = pane.project(SlotSequence.all(pane.size()), source, this::link);
            assertEquals(this.link(0), pane.element(0));
            assertEquals(before, SparrowUiTestRuntime.asyncRuns());

            source.set(List.of(1));
            assertEquals(this.link(1), pane.element(0));
            assertEquals(before + 1, SparrowUiTestRuntime.asyncRuns());
            projection.close();
            source.set(List.of(2));
            assertEquals(this.link(1), pane.element(0));
            assertEquals(before + 1, SparrowUiTestRuntime.asyncRuns());
        } finally {
            SparrowUiTestRuntime.restorePlugin();
        }
    }

    @Test
    void shorterSequenceClearsTheRemainingSlots() {
        NormalPane pane = Pane.empty(3, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0, 1, 2));
        pane.project(SlotSequence.all(pane.size()), source, this::link, Runnable::run);
        source.set(List.of(5));

        assertEquals(this.link(5), pane.element(0));
        assertSame(Element.empty(), pane.element(1));
        assertSame(Element.empty(), pane.element(2));
    }

    @Test
    void longerSequenceIsTruncatedWithoutWarning() {
        List<String> warnings = new ArrayList<>();
        SparrowUI.getInstance().setWarningHandler(warnings::add);
        SparrowUI.getInstance().warningsEnabled(true);
        NormalPane pane = Pane.empty(2, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0, 1, 2, 3));
        pane.project(SlotSequence.all(pane.size()), source, this::link, Runnable::run);

        assertEquals(this.link(0), pane.element(0));
        assertEquals(this.link(1), pane.element(1));
        assertEquals(List.of(), warnings, "切出多长交给上游决定, 截断不告警");
    }

    @Test
    void onlyChangedSlotsAreWritten() {
        NormalPane pane = Pane.empty(3, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0, 1, 2));
        pane.project(SlotSequence.all(pane.size()), source, this::link, Runnable::run);
        AtomicInteger[] writes = this.countWrites(pane, 3);
        source.set(List.of(0, 1, 7));

        assertEquals(0, writes[0].get(), "内容没变的槽位不该写");
        assertEquals(0, writes[1].get(), "内容没变的槽位不该写");
        assertEquals(1, writes[2].get());
    }

    @Test
    void paneLinkSequenceAlsoSkipsUnchangedSlots() {
        NormalPane pane = Pane.empty(2, 1);
        NormalPane child = Pane.empty(2, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0, 1));
        pane.project(SlotSequence.all(pane.size()), source, slot -> Element.pane(child, slot), Runnable::run);
        AtomicInteger[] writes = this.countWrites(pane, 2);
        source.set(List.of(0, 1));
        source.set(List.of(1, 1));

        assertEquals(1, writes[0].get(), "PaneLink 也按值判断, 指向没变就不写");
        assertEquals(0, writes[1].get());
    }

    @Test
    void externalWriteInsideTheRegionIsCorrectedOnTheNextRound() {
        NormalPane pane = Pane.empty(2, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0, 1));
        pane.project(SlotSequence.all(pane.size()), source, this::link, Runnable::run);
        pane.setElement(0, Element.empty());
        source.set(List.of(0, 3));

        assertEquals(this.link(0), pane.element(0), "下一轮求值按序列把它改回来");
        assertEquals(this.link(3), pane.element(1));
    }

    @Test
    void burstOfInvalidationsCollapsesIntoOneRound() {
        ManualExecutor executor = new ManualExecutor();
        NormalPane pane = Pane.empty(1, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));
        AtomicInteger rounds = new AtomicInteger();
        pane.project(SlotSequence.all(pane.size()), source, slot -> {
            rounds.incrementAndGet();
            return this.link(slot);
        }, executor);

        assertEquals(1, rounds.get(), "首次求值就地完成");
        source.set(List.of(1));
        source.set(List.of(2));
        source.set(List.of(3));

        assertEquals(1, executor.pending(), "连着来的失效只排一轮");
        executor.drain();

        assertEquals(2, rounds.get());
        assertEquals(this.link(3), pane.element(0), "跑的那一轮读到的是最新序列");
    }

    @Test
    void invalidationDuringARoundTriggersAnotherRound() {
        ManualExecutor executor = new ManualExecutor();
        NormalPane pane = Pane.empty(1, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));
        AtomicInteger rounds = new AtomicInteger();
        pane.project(SlotSequence.all(pane.size()), source, slot -> {
            if (rounds.incrementAndGet() == 2) {
                source.set(List.of(8));
            }
            return this.link(slot);
        }, executor);
        source.set(List.of(1));
        executor.drain();

        assertEquals(3, rounds.get(), "跑的期间来的失效应当另起一轮");
        assertEquals(this.link(8), pane.element(0));
    }

    @Test
    void firstRoundIsNotReenteredByTheInvalidationItTriggers() {
        NormalPane pane = Pane.empty(1, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));
        List<Integer> visited = new ArrayList<>();
        pane.project(SlotSequence.all(pane.size()), source, slot -> {
            visited.add(slot);
            if (visited.size() == 1) {
                source.set(List.of(4));
            }
            return this.link(slot);
        }, Runnable::run);

        assertEquals(List.of(0, 4), visited);
        assertEquals(this.link(4), pane.element(0), "第二轮必须在首轮写完之后才跑");
    }

    @Test
    void closeStopsFollowingTheSequence() {
        NormalPane pane = Pane.empty(1, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));
        SlotProjection projection = pane.project(SlotSequence.all(pane.size()), source, this::link, Runnable::run);

        assertFalse(projection.isClosed());
        projection.close();
        source.set(List.of(5));

        assertTrue(projection.isClosed());
        assertEquals(this.link(0), pane.element(0), "关闭后写进去的内容原样留着, 也不再跟随序列");
    }

    @Test
    void paneKeepsTheProjectionAliveWithoutHoldingTheReturnValue() {
        NormalPane pane = Pane.empty(1, 1);
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));
        pane.project(SlotSequence.all(pane.size()), source, this::link, Runnable::run);
        GcSupport.pressure();
        source.set(List.of(5));

        assertEquals(this.link(5), pane.element(0), "投影挂在 Pane 上, 丢掉返回值不该让它悄悄失效");
    }

    @Test
    void droppingThePaneReleasesTheProjection() {
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));
        NormalPane pane = Pane.empty(1, 1);
        SlotProjection projection = pane.project(SlotSequence.all(pane.size()), source, this::link, Runnable::run);
        WeakReference<SlotProjection> probe = new WeakReference<>(projection);
        projection = null;
        pane = null;
        GcSupport.awaitCollected(probe);
    }

    @Test
    void rejectsSlotsFromAnotherPane() {
        NormalPane pane = Pane.empty(1, 1);
        SlotSequence foreign = SlotSequence.all(new PaneSize(3, 1));
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));

        assertThrows(
                IllegalArgumentException.class,
                () -> pane.project(foreign, source, this::link, Runnable::run)
        );
    }

    @Test
    void attachElementsWritesTheGivenElements() {
        NormalPane pane = Pane.empty(2, 1);
        MutableSignal<List<Element>> source = Signal.of(List.of(this.link(0), this.link(1)));
        pane.projectElements(SlotSequence.all(pane.size()), source, Runnable::run);

        assertEquals(this.link(1), pane.element(1));
        source.set(List.of(this.link(4)));

        assertEquals(this.link(4), pane.element(0));
        assertSame(Element.empty(), pane.element(1));
    }

    @Test
    void builderDeclaresProjectionByIdentifier() {
        MutableSignal<List<Integer>> source = Signal.of(List.of(0, 1));
        NormalPane pane = Pane.builder("VV#")
                .addIngredient('#', Element.empty())
                .addIngredient('V', source, this::link, Runnable::run)
                .build();

        assertEquals(this.link(0), pane.element(0));
        assertEquals(this.link(1), pane.element(1));
        source.set(List.of(4, 5));

        assertEquals(this.link(4), pane.element(0), "build 之后仍然跟随序列");
        assertEquals(this.link(5), pane.element(1));
    }

    @Test
    void everyBuiltPaneGetsItsOwnProjection() {
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));
        Pane.Builder<NormalPane, ?> builder = Pane.builder("V").addIngredient('V', source, this::link, Runnable::run);
        NormalPane first = builder.build();
        NormalPane second = builder.build();
        source.set(List.of(6));

        assertEquals(this.link(6), first.element(0));
        assertEquals(this.link(6), second.element(0), "同一个 Builder 建出的每个 Pane 各自跟随");
    }

    @Test
    void lastDeclarationForAnIdentifierWins() {
        MutableSignal<List<Integer>> first = Signal.of(List.of(0));
        MutableSignal<List<Integer>> second = Signal.of(List.of(1));
        NormalPane pane = Pane.builder("V")
                .addIngredient('V', first, this::link, Runnable::run)
                .addIngredient('V', second, this::link, Runnable::run)
                .build();

        assertEquals(this.link(1), pane.element(0));
        first.set(List.of(6));

        assertEquals(this.link(1), pane.element(0), "只有最后声明的那条投影生效");
    }

    @Test
    void staticIngredientAndProjectionOverrideEachOtherByDeclarationOrder() {
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));
        Element fixed = Element.inventory(this.vault, 5);
        NormalPane projectionWins = Pane.builder("V")
                .addIngredient('V', fixed)
                .addIngredient('V', source, this::link, Runnable::run)
                .build();

        assertEquals(this.link(0), projectionWins.element(0), "投影声明在后, 投影生效");
        NormalPane staticWins = Pane.builder("V")
                .addIngredient('V', source, this::link, Runnable::run)
                .addIngredient('V', fixed)
                .build();

        assertEquals(fixed, staticWins.element(0), "静态声明在后, 静态生效");
        source.set(List.of(3));

        assertEquals(fixed, staticWins.element(0), "被挤掉的投影不该还在写");
    }

    @Test
    void builderRejectsAnIdentifierMissingFromTheStructure() {
        MutableSignal<List<Integer>> source = Signal.of(List.of(0));

        assertThrows(
                IllegalArgumentException.class,
                () -> Pane.builder("VV#").addIngredient('Z', source, this::link, Runnable::run)
        );
    }

    private AtomicInteger[] countWrites(NormalPane pane, int length) {
        AtomicInteger[] writes = new AtomicInteger[length];
        for (int slot = 0; slot < length; slot++) {
            writes[slot] = new AtomicInteger();
            AtomicInteger counter = writes[slot];
            pane.attach(slot, ignoredHost -> counter.incrementAndGet());
        }
        return writes;
    }

    private Element link(int slot) {
        return Element.inventory(this.vault, slot);
    }
}
