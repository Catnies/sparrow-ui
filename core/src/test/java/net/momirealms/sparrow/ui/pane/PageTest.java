package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.item.AttachSupport;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.pane.page.Page;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.ManualExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageTest {

    private ServerMock server;
    private Player player;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.player = this.server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void slicesContentIntoPages() {
        Page<Integer> pages = Page.of(Signal.of(numbers(7)), 3);

        assertEquals(List.of(0, 1, 2), pages.content().get());
        pages.advance(1);

        assertEquals(List.of(3, 4, 5), pages.content().get());
        pages.advance(1);

        assertEquals(List.of(6), pages.content().get(), "最后一页不满也照样给出来");
    }

    @Test
    void pageCountRoundsUpAndKeepsOnePageWhenEmpty() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(0));
        Page<Integer> pages = Page.of(source, 3);

        assertEquals(1, pages.count().get(), "没有内容也还有一页空的");
        source.set(numbers(6));

        assertEquals(2, pages.count().get());
        source.set(numbers(7));

        assertEquals(3, pages.count().get());
    }

    @Test
    void rejectsNonPositivePageSize() {
        assertThrows(IllegalArgumentException.class, () -> Page.of(Signal.of(numbers(3)), 0));
    }

    @Test
    void advanceStopsAtBothEnds() {
        Page<Integer> pages = Page.of(Signal.of(numbers(7)), 3);
        pages.advance(-1);

        assertEquals(0, pages.page().get(), "第一页再往前翻不动");
        pages.advance(10);

        assertEquals(2, pages.page().get(), "一次翻过头停在最后一页");
        pages.advance(1);

        assertEquals(2, pages.page().get(), "最后一页再往后翻不动");
    }

    @Test
    void steppingPastTheEndDoesNotResurfaceWhenContentGrows() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(7));
        Page<Integer> pages = Page.of(source, 3);
        pages.advance(2);
        pages.advance(1);
        pages.advance(1);
        pages.advance(1);
        source.set(numbers(30));

        assertEquals(2, pages.page().get(), "点不动的那几下不该在内容变多之后补上");
    }

    @Test
    void pageIndexFallsBackWhenContentShrinks() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(9));
        Page<Integer> pages = Page.of(source, 3);
        pages.setPage(2);
        source.set(numbers(2));

        assertEquals(0, pages.page().get(), "内容不够时页码回夹");
        assertEquals(List.of(0, 1), pages.content().get(), "回夹之后页码与内容一致, 不是一页空白");
    }

    @Test
    void clampedPageComesBackWhenContentGrowsAgain() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(9));
        Page<Integer> pages = Page.of(source, 3);
        pages.setPage(2);
        source.set(numbers(2));
        source.set(numbers(9));

        assertEquals(2, pages.page().get(), "回夹只改读到的页码, 使用方要求的那个页码留着");
    }

    @Test
    void jumpIsClampedWhenItIsWritten() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(9));
        Page<Integer> pages = Page.of(source, 3);
        pages.setPage(99);

        assertEquals(2, pages.page().get());
        source.set(numbers(30));

        assertEquals(2, pages.page().get(), "跳页在写入时就夹住了, 内容变多不会继续往后跑");
    }

    @Test
    void invalidationDoesNotPullTheUserDerivation() {
        AtomicInteger derivations = new AtomicInteger();
        MutableSignal<Integer> size = Signal.of(7);
        Signal<List<Integer>> content = size.map(count -> {
            derivations.incrementAndGet();
            return numbers(count);
        });
        Page<Integer> pages = Page.of(content, 3);
        pages.content().get();
        Subscription downstream = pages.page().onDirty(() -> {
        });
        derivations.set(0);
        size.set(9);

        assertEquals(0, derivations.get(), "内容派生是使用方的重活, 只能在拉取时跑, 不能被失效路径拉起来");
        assertFalse(downstream.isClosed());
    }

    @Test
    void variablePageSizeSlicesEachPageByItsOwnSize() {
        Page<Integer> pages = Page.of(Signal.of(numbers(9)), index -> switch (index % 3) {
            case 0 -> 3;
            case 1 -> 2;
            default -> 1;
        });

        assertEquals(4, pages.count().get(), "3 + 2 + 1 排完 6 条, 剩下 3 条还要一页");
        assertEquals(List.of(0, 1, 2), pages.content().get());
        pages.advance(1);

        assertEquals(List.of(3, 4), pages.content().get());
        pages.advance(1);

        assertEquals(List.of(5), pages.content().get());
        pages.advance(1);

        assertEquals(List.of(6, 7, 8), pages.content().get(), "第四页回到三行");
    }

    @Test
    void asyncContentWorksWithoutASeparateFactory() {
        ManualExecutor executor = new ManualExecutor();
        Signal<List<Integer>> content = Signal.async(List.of(), executor, () -> numbers(9));
        Page<Integer> pages = Page.of(content, index -> index % 2 == 0 ? 3 : 2);

        assertEquals(List.of(), pages.content().get(), "首载完成前给的是占位值");
        assertEquals(1, pages.count().get());
        executor.drain();

        assertEquals(4, pages.count().get(), "3 + 2 + 3 排完 8 条, 剩下 1 条还要一页");
        assertEquals(List.of(0, 1, 2), pages.content().get());
        pages.advance(1);

        assertEquals(List.of(3, 4), pages.content().get());
    }

    @Test
    void contentSizeReportsWhatIsActuallyOnThePage() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(7));
        Page<Integer> pages = Page.of(source, 3);

        assertEquals(3, pages.contentSize().get());
        pages.advance(2);

        assertEquals(1, pages.contentSize().get(), "最后一页只排上一条, 不是每页的 3");
        source.set(numbers(0));

        assertEquals(0, pages.contentSize().get(), "没有内容时是 0");
    }

    @Test
    void contentSizeFollowsVariablePageSizes() {
        Page<Integer> pages = Page.of(Signal.of(numbers(9)), index -> index % 3 == 0 ? 3 : index % 3 == 1 ? 2 : 1);

        assertEquals(3, pages.contentSize().get());
        pages.advance(1);

        assertEquals(2, pages.contentSize().get());
        pages.advance(1);

        assertEquals(1, pages.contentSize().get());
    }

    @Test
    void offsetOfAccumulatesEarlierPageSizes() {
        IntUnaryOperator sizeOf = index -> index % 3 == 0 ? 3 : index % 3 == 1 ? 2 : 1;

        assertEquals(0, Page.offsetOf(0, sizeOf));
        assertEquals(3, Page.offsetOf(1, sizeOf));
        assertEquals(5, Page.offsetOf(2, sizeOf), "3 + 2");
        assertEquals(6, Page.offsetOf(3, sizeOf), "3 + 2 + 1");
    }

    @Test
    void countOfCountsPagesUntilTheContentRunsOut() {
        IntUnaryOperator sizeOf = index -> index % 3 == 0 ? 3 : index % 3 == 1 ? 2 : 1;

        assertEquals(1, Page.countOf(0, sizeOf), "没有内容也还有一页空的");
        assertEquals(1, Page.countOf(3, sizeOf));
        assertEquals(2, Page.countOf(4, sizeOf), "第一页 3 条装不下第 4 条");
        assertEquals(2, Page.countOf(5, sizeOf), "3 + 2");
        assertEquals(3, Page.countOf(6, sizeOf), "3 + 2 + 1");
        assertEquals(4, Page.countOf(7, sizeOf), "第四页回到三条");
        assertEquals(4, Page.countOf(9, sizeOf));
    }

    @Test
    void remoteVariablePageSizeAgreesWithLocalSlicing() {
        List<Integer> all = numbers(9);
        IntUnaryOperator sizeOf = index -> index % 3 == 0 ? 3 : index % 3 == 1 ? 2 : 1;
        KeyedSignal<Integer, List<Integer>> partitions = KeyedSignal.of(pageNo -> {
            int from = Page.offsetOf(pageNo, sizeOf);
            return all.subList(Math.min(from, all.size()), Math.min(from + sizeOf.applyAsInt(pageNo), all.size()));
        });
        Page<Integer> remote = Page.of(partitions, Signal.of(Page.countOf(all.size(), sizeOf)));
        Page<Integer> local = Page.of(Signal.of(all), sizeOf);

        assertEquals(local.count().get(), remote.count().get());
        for (int page = 0; page < local.count().get(); page++) {
            local.setPage(page);
            remote.setPage(page);

            assertEquals(local.content().get(), remote.content().get(), "第 " + page + " 页两条路走出来该是同一段");
        }
    }

    @Test
    void variablePageSizeRejectsNonPositiveSize() {
        Page<Integer> pages = Page.of(Signal.of(numbers(9)), ignoredIndex -> 0);

        assertThrows(IllegalArgumentException.class, () -> pages.count().get());
    }

    @Test
    void handWiredButtonTurnsThePageOnClick() {
        Page<Integer> pages = Page.of(Signal.of(numbers(7)), 3);
        Item next = Item.builder()
                .dependsOn(pages.page())
                .setItemProvider(ignoredContext -> ItemStack.empty())
                .addClickHandler(ignoredClick -> pages.advance(1))
                .build();
        next.handleClick(this.click());

        assertEquals(1, pages.page().get());
    }

    @Test
    void pageIndexCarriesPageCountChangesToItsDependents() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(9));
        Page<Integer> pages = Page.of(source, 3);
        Item next = Item.builder()
                .dependsOn(pages.page())
                .setItemProvider(ignoredContext -> ItemStack.empty())
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        AttachSupport.attach(next, ignoredItem -> invalidations.incrementAndGet());
        source.set(numbers(3));

        assertEquals(0, pages.page().get());
        assertEquals(1, invalidations.get(), "只挂 pageIndex 就够, 总页数经它传导过来");
    }

    @Test
    void remotePagesLoadOnlyWhenSelected() {
        List<Integer> loaded = new ArrayList<>();
        KeyedSignal<Integer, List<Integer>> partitions = KeyedSignal.of(pageNo -> {
            loaded.add(pageNo);
            return List.of(pageNo * 10, pageNo * 10 + 1);
        });
        Page<Integer> pages = Page.of(partitions, Signal.of(3));

        assertEquals(List.of(0, 1), pages.content().get());
        assertEquals(List.of(0), loaded, "只装载当前这一页");
        pages.advance(1);

        assertEquals(List.of(10, 11), pages.content().get());
        assertEquals(List.of(0, 1), loaded, "翻到哪一页才装载哪一页");
    }

    @Test
    void remotePageCountIsNormalisedToAtLeastOne() {
        KeyedSignal<Integer, List<Integer>> partitions = KeyedSignal.of(pageNo -> List.of(pageNo));
        Page<Integer> pages = Page.of(partitions, Signal.of(0));

        assertEquals(1, pages.count().get());
        assertEquals(0, pages.page().get(), "页数为 0 时夹取区间仍然合法");
    }

    @Test
    void paginationFillsAPaneThroughTheBuilder() {
        VirtualInventory vault = new VirtualInventory(9);
        Page<Integer> pages = Page.of(Signal.of(numbers(5)), 3);
        NormalPane pane = Pane.builder("VVV", "P#N")
                .addIngredient('V', pages.content(), slot -> Element.inventory(vault, slot), Runnable::run)
                .addIngredient('P', this.pageButton(pages, -1))
                .addIngredient('N', this.pageButton(pages, 1))
                .build();

        assertEquals(Element.inventory(vault, 0), pane.element(0));
        assertEquals(Element.inventory(vault, 2), pane.element(2));
        pane.item(5).handleClick(this.click());

        assertEquals(Element.inventory(vault, 3), pane.element(0));
        assertEquals(Element.inventory(vault, 4), pane.element(1));
        assertSame(Element.empty(), pane.element(2), "最后一页不满, 余下的槽位清空");
    }

    @Test
    void paginationIsCollectedAlongWithTheProjectedPane() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(7));
        NormalPane pane = Pane.empty(3, 1);
        Page<Integer> pages = Page.of(source, 3);
        pane.project(SlotSequence.all(pane.size()), pages.content(), ignoredValue -> Element.empty(), Runnable::run);
        WeakReference<Page<Integer>> probe = new WeakReference<>(pages);
        pages = null;
        pane = null;
        GcSupport.awaitCollected(probe);
    }

    @Test
    void bulkContentNeedsNoSignals() {
        List<Integer> content = new ArrayList<>(numbers(7));
        Page<Integer> pages = Page.of(content, 3);

        assertEquals(List.of(0, 1, 2), pages.content().get());
        pages.advance(2);

        assertEquals(List.of(6), pages.content().get());
        content.clear();
        pages.setPage(0);

        assertEquals(List.of(0, 1, 2), pages.content().get(), "内容在丢进来那一刻定死, 之后改原 List 不算数");
    }

    @Test
    void bulkContentWithVariablePageSizes() {
        Page<Integer> pages = Page.of(numbers(6), index -> index % 2 == 0 ? 3 : 2);

        assertEquals(3, pages.count().get(), "3 + 2 排完 5 条, 剩下 1 条还要一页");
        pages.advance(1);

        assertEquals(List.of(3, 4), pages.content().get());
    }

    @Test
    void asyncFactoryLoadsPagesOnDemand() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> loaded = new ArrayList<>();
        Page<Integer> pages = Page.async(executor, pageNo -> {
            loaded.add(pageNo);
            return List.of(pageNo * 10, pageNo * 10 + 1);
        }, () -> 3);

        assertEquals(List.of(), pages.content().get(), "首载完成前给的是占位值");
        executor.drain();

        assertEquals(List.of(0, 1), pages.content().get());
        assertEquals(3, pages.count().get());
        assertEquals(List.of(0), loaded, "只装载看过的那一页");
        pages.advance(1);

        assertEquals(List.of(), pages.content().get(), "翻过去那一刻才排装载, 完成前还是占位值");
        executor.drain();

        assertEquals(List.of(10, 11), pages.content().get());
        assertEquals(List.of(0, 1), loaded, "翻到哪一页才装载哪一页");
    }

    @Test
    void asyncPageCountStartsAtOneUntilLoaded() {
        ManualExecutor executor = new ManualExecutor();
        Page<Integer> pages = Page.async(executor, pageNo -> List.of(pageNo), () -> 4);

        assertEquals(1, pages.count().get(), "页数装载完成前按一页算");
        pages.advance(5);

        assertEquals(0, pages.page().get(), "只有一页时翻不动");
        executor.drain();

        assertEquals(4, pages.count().get());
        assertEquals(0, pages.page().get(), "占位期间点不动的那几下不该在页数就位之后补上");
    }

    @Test
    void refreshReloadsTheCurrentPageAndTheCount() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger version = new AtomicInteger(0);
        Page<Integer> pages = Page.async(executor, pageNo -> List.of(pageNo * 10 + version.get()), () -> 2 + version.get());
        pages.content().get();
        executor.drain();

        assertEquals(List.of(0), pages.content().get());
        assertEquals(2, pages.count().get());
        version.set(1);

        assertEquals(List.of(0), pages.content().get());
        pages.refresh();
        executor.drain();

        assertEquals(List.of(1), pages.content().get(), "当前页重新装载");
        assertEquals(3, pages.count().get(), "总页数一起重查");
    }

    @Test
    void refreshAndPrefetchDoNothingOnLocalContent() {
        Page<Integer> pages = Page.of(numbers(7), 3);
        pages.advance(1);
        pages.refresh();
        pages.prefetch(1);

        assertEquals(List.of(3, 4, 5), pages.content().get(), "内容本来就在手里, 没有可刷新的东西");
    }

    @Test
    void prefetchLoadsThePageAhead() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> loaded = new ArrayList<>();
        Page<Integer> pages = Page.async(executor, pageNo -> {
            loaded.add(pageNo);
            return List.of(pageNo);
        }, () -> 3);
        pages.content().get();
        executor.drain();
        pages.prefetch(1);
        executor.drain();

        assertEquals(List.of(0, 1), loaded, "下一页提前装好");
        pages.advance(1);

        assertEquals(List.of(1), pages.content().get(), "翻过去当场就是真值, 不用再等装载");
    }

    @Test
    void prefetchClampsToTheExistingPages() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> loaded = new ArrayList<>();
        Page<Integer> pages = Page.async(executor, pageNo -> {
            loaded.add(pageNo);
            return List.of(pageNo);
        }, () -> 3);
        pages.content().get();
        executor.drain();
        pages.prefetch(99);
        executor.drain();

        assertEquals(List.of(0, 2), loaded, "越界的预取夹到最后一页, 不会拿不存在的页码去查");
    }

    @Test
    void builderAcceptsThePageDirectly() {
        VirtualInventory vault = new VirtualInventory(9);
        Page<Integer> pages = Page.of(List.of(0, 1, 2, 3, 4), 3);
        NormalPane pane = Pane.builder("VVV", "P#N")
                .addIngredient('V', pages, slot -> Element.inventory(vault, slot), Runnable::run)
                .addIngredient('N', this.pageButton(pages, 1))
                .build();

        assertEquals(Element.inventory(vault, 0), pane.element(0));
        pane.item(5).handleClick(this.click());

        assertEquals(Element.inventory(vault, 3), pane.element(0));
        assertSame(Element.empty(), pane.element(2), "最后一页不满, 余下的槽位清空");
    }

    @Test
    void builderAcceptsAPageOfItemsWithoutAMapper() {
        Item first = Item.builder().setItemProvider(ignoredContext -> ItemStack.empty()).build();
        Item second = Item.builder().setItemProvider(ignoredContext -> ItemStack.empty()).build();
        Page<Item> pages = Page.of(List.of(first, second), 1);
        NormalPane pane = Pane.builder("V").addIngredient('V', pages).build();

        assertEquals(Element.item(first), pane.element(0), "内容已经是 Item 时不需要 toElement");
    }

    private Item pageButton(Page<?> pages, int step) {
        return Item.builder()
                .dependsOn(pages.page())
                .setItemProvider(ignoredContext -> ItemStack.empty())
                .addClickHandler(ignoredClick -> pages.advance(step))
                .build();
    }

    private ItemClick click() {
        return new ItemClick(this.player, ClickType.LEFT, new WindowStub(this.player), ItemStack.empty(), 0);
    }

    private static List<Integer> numbers(int count) {
        List<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(i);
        }
        return List.copyOf(values);
    }
}
