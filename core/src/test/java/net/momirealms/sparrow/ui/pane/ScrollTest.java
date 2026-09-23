package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.item.AttachSupport;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.pane.page.Scroll;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScrollTest {

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
    void scrollsOneLineAtATimeSoScreensOverlap() {
        Scroll<Integer> scroll = Scroll.vertical(Signal.of(numbers(12)), 3, 2);

        assertEquals(List.of(0, 1, 2, 3, 4, 5), scroll.content().get());
        scroll.advance(1);

        assertEquals(List.of(3, 4, 5, 6, 7, 8), scroll.content().get(), "滚一次只换一行, 前后两屏是重叠的");
        scroll.advance(1);

        assertEquals(List.of(6, 7, 8, 9, 10, 11), scroll.content().get());
    }

    @Test
    void lineCountCountsPartialLastLine() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(0));
        Scroll<Integer> scroll = Scroll.vertical(source, 3, 2);

        assertEquals(1, scroll.lineCount().get(), "没有内容也还有一行空的");
        source.set(numbers(3));

        assertEquals(1, scroll.lineCount().get());
        source.set(numbers(4));

        assertEquals(2, scroll.lineCount().get(), "第 4 条要占第二行");
    }

    @Test
    void maxLineIsZeroWhileTheContentFitsOnOneScreen() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(6));
        Scroll<Integer> scroll = Scroll.vertical(source, 3, 2);

        assertEquals(0, scroll.maxLine().get(), "正好一屏, 哪边都滚不动");
        source.set(numbers(7));

        assertEquals(1, scroll.maxLine().get(), "多出一行才滚得动一行");
        source.set(numbers(12));

        assertEquals(2, scroll.maxLine().get(), "4 行内容显示 2 行, 能停 3 个位置");
    }

    @Test
    void rejectsNonPositiveShape() {
        assertThrows(IllegalArgumentException.class, () -> Scroll.vertical(Signal.of(numbers(3)), 0, 2));
        assertThrows(IllegalArgumentException.class, () -> Scroll.vertical(Signal.of(numbers(3)), 3, 0));
    }

    @Test
    void advanceStopsAtBothEnds() {
        Scroll<Integer> scroll = Scroll.vertical(Signal.of(numbers(12)), 3, 2);
        scroll.advance(-1);

        assertEquals(0, scroll.line().get(), "顶上再往上滚不动");
        scroll.advance(10);

        assertEquals(2, scroll.line().get(), "一次滚过头停在最后一屏");
        scroll.advance(1);

        assertEquals(2, scroll.line().get(), "底下再往下滚不动");
    }

    @Test
    void scrollingPastTheEndDoesNotResurfaceWhenContentGrows() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(12));
        Scroll<Integer> scroll = Scroll.vertical(source, 3, 2);
        scroll.advance(2);
        scroll.advance(1);
        scroll.advance(1);
        scroll.advance(1);
        source.set(numbers(60));

        assertEquals(2, scroll.line().get(), "滚不动的那几下不该在内容变多之后补上");
    }

    @Test
    void lineFallsBackWhenContentShrinks() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(12));
        Scroll<Integer> scroll = Scroll.vertical(source, 3, 2);
        scroll.setLine(2);
        source.set(numbers(4));

        assertEquals(0, scroll.line().get(), "内容不够时行偏移回夹");
        assertEquals(List.of(0, 1, 2, 3), scroll.content().get(), "回夹之后行偏移与内容一致, 不是一屏空白");
    }

    @Test
    void clampedLineComesBackWhenContentGrowsAgain() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(12));
        Scroll<Integer> scroll = Scroll.vertical(source, 3, 2);
        scroll.setLine(2);
        source.set(numbers(4));
        source.set(numbers(12));

        assertEquals(2, scroll.line().get(), "回夹只改读到的行偏移, 使用方要求的那个留着");
    }

    @Test
    void jumpIsClampedWhenItIsWritten() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(12));
        Scroll<Integer> scroll = Scroll.vertical(source, 3, 2);
        scroll.setLine(99);

        assertEquals(2, scroll.line().get());
        source.set(numbers(60));

        assertEquals(2, scroll.line().get(), "跳行在写入时就夹住了, 内容变多不会继续往下跑");
    }

    @Test
    void contentSizeReportsWhatIsActuallyOnScreen() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(7));
        Scroll<Integer> scroll = Scroll.vertical(source, 3, 2);

        assertEquals(6, scroll.contentSize().get());
        scroll.advance(1);

        assertEquals(4, scroll.contentSize().get(), "滚到底那一屏只剩 4 条");
        source.set(numbers(0));

        assertEquals(0, scroll.contentSize().get(), "没有内容时是 0");
    }

    @Test
    void asyncContentWorksWithoutASeparateFactory() {
        ManualExecutor executor = new ManualExecutor();
        Signal<List<Integer>> content = Signal.async(List.of(), executor, () -> numbers(12));
        Scroll<Integer> scroll = Scroll.vertical(content, 3, 2);

        assertEquals(List.of(), scroll.content().get(), "首载完成前给的是占位值");
        assertEquals(0, scroll.maxLine().get());
        executor.drain();

        assertEquals(2, scroll.maxLine().get(), "装载完成后行数与最大行偏移一起更新");
        assertEquals(List.of(0, 1, 2, 3, 4, 5), scroll.content().get());
    }

    @Test
    void handWiredButtonScrollsOnClick() {
        Scroll<Integer> scroll = Scroll.vertical(Signal.of(numbers(12)), 3, 2);
        Item down = Item.builder()
                .dependsOn(scroll.line())
                .setItemProvider(ignoredContext -> ItemStack.empty())
                .addClickHandler(ignoredClick -> scroll.advance(1))
                .build();
        down.handleClick(this.click());

        assertEquals(1, scroll.line().get());
    }

    @Test
    void lineCarriesMaxLineChangesToItsDependents() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(12));
        Scroll<Integer> scroll = Scroll.vertical(source, 3, 2);
        Item down = Item.builder()
                .dependsOn(scroll.line())
                .setItemProvider(ignoredContext -> ItemStack.empty())
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        AttachSupport.attach(down, ignoredItem -> invalidations.incrementAndGet());
        source.set(numbers(6));

        assertEquals(0, scroll.line().get());
        assertEquals(1, invalidations.get(), "只挂 line 就够, maxLine 经它传导过来");
    }

    @Test
    void scrollFillsAPaneThroughTheBuilder() {
        VirtualInventory vault = new VirtualInventory(12);
        Scroll<Integer> scroll = Scroll.vertical(Signal.of(numbers(12)), 3, 2);
        NormalPane pane = Pane.builder("VVV", "VVV", "U#D")
                .addIngredient('V', scroll.content(), slot -> Element.inventory(vault, slot), Runnable::run)
                .addIngredient('D', this.scrollButton(scroll, 1))
                .addIngredient('U', this.scrollButton(scroll, -1))
                .build();

        assertEquals(Element.inventory(vault, 0), pane.element(0));
        assertEquals(Element.inventory(vault, 5), pane.element(5));
        pane.item(8).handleClick(this.click());

        assertEquals(Element.inventory(vault, 3), pane.element(0));
        assertEquals(Element.inventory(vault, 8), pane.element(5));
    }

    @Test
    void scrollIsCollectedAlongWithTheProjectedPane() {
        MutableSignal<List<Integer>> source = Signal.of(numbers(12));
        NormalPane pane = Pane.empty(3, 2);
        Scroll<Integer> scroll = Scroll.vertical(source, 3, 2);
        pane.project(SlotSequence.all(pane.size()), scroll.content(), ignoredValue -> Element.empty(), Runnable::run);
        WeakReference<Scroll<Integer>> probe = new WeakReference<>(scroll);
        scroll = null;
        pane = null;
        GcSupport.awaitCollected(probe);
    }

    @Test
    void horizontalScrollsOneColumnAtATime() {
        Scroll<Integer> scroll = Scroll.horizontal(numbers(8), 3, 2);

        assertEquals(List.of(0, 1, 2, 3, 4, 5), scroll.content().get(), "前两列");
        scroll.advance(1);

        assertEquals(List.of(3, 4, 5, 6, 7), scroll.content().get(), "滚一次只换一列, 最后一列不满也照样给出来");
        assertEquals(1, scroll.maxLine().get(), "3 列内容显示 2 列, 能停 2 个位置");
    }

    @Test
    void bulkContentScrollsWithoutSignals() {
        List<Integer> content = new ArrayList<>(numbers(12));
        Scroll<Integer> scroll = Scroll.vertical(content, 3, 2);
        scroll.advance(1);

        assertEquals(List.of(3, 4, 5, 6, 7, 8), scroll.content().get());
        content.clear();
        scroll.setLine(0);

        assertEquals(List.of(0, 1, 2, 3, 4, 5), scroll.content().get(), "内容在丢进来那一刻定死, 之后改原 List 不算数");
    }

    @Test
    void horizontalContentLandsColumnMajorThroughTheBuilder() {
        VirtualInventory vault = new VirtualInventory(12);
        Scroll<Integer> scroll = Scroll.horizontal(numbers(8), 3, 2);
        NormalPane pane = Pane.builder("VV", "VV", "VV")
                .addIngredient('V', scroll, slot -> Element.inventory(vault, slot), Runnable::run)
                .build();

        assertEquals(Element.inventory(vault, 0), pane.element(0), "第一条落在第一列头上");
        assertEquals(Element.inventory(vault, 1), pane.element(2), "第二条落在第一列第二格");
        assertEquals(Element.inventory(vault, 3), pane.element(1), "第四条落到第二列头上");
        assertEquals(Element.inventory(vault, 5), pane.element(5));
        scroll.advance(1);

        assertEquals(Element.inventory(vault, 3), pane.element(0), "滚一列之后第二列的内容挪到第一列");
        assertEquals(Element.inventory(vault, 6), pane.element(1), "第三列滚进来");
        assertSame(Element.empty(), pane.element(5), "最后一列不满, 余下的槽位清空");
    }

    @Test
    void verticalBuilderIngredientKeepsRowMajorOrder() {
        VirtualInventory vault = new VirtualInventory(12);
        Scroll<Integer> scroll = Scroll.vertical(numbers(12), 3, 2);
        NormalPane pane = Pane.builder("VVV", "VVV")
                .addIngredient('V', scroll, slot -> Element.inventory(vault, slot), Runnable::run)
                .build();

        assertEquals(Element.inventory(vault, 0), pane.element(0));
        assertEquals(Element.inventory(vault, 1), pane.element(1), "竖着滚还是行主序, 第二条落在右边一格");
        scroll.advance(1);

        assertEquals(Element.inventory(vault, 3), pane.element(0), "滚一行之后第二行的内容挪到第一行");
    }

    @Test
    void builderAcceptsAScrollOfItemsWithoutAMapper() {
        Item first = Item.builder().setItemProvider(ignoredContext -> ItemStack.empty()).build();
        Item second = Item.builder().setItemProvider(ignoredContext -> ItemStack.empty()).build();
        Scroll<Item> scroll = Scroll.vertical(List.of(first, second), 1, 1);
        NormalPane pane = Pane.builder("V").addIngredient('V', scroll).build();

        assertEquals(Element.item(first), pane.element(0), "内容已经是 Item 时不需要 toElement");
    }

    private Item scrollButton(Scroll<?> scroll, int step) {
        return Item.builder()
                .dependsOn(scroll.line())
                .setItemProvider(ignoredContext -> ItemStack.empty())
                .addClickHandler(ignoredClick -> scroll.advance(step))
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
