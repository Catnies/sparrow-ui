package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.InventorySequence;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemBuilder;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.page.Page;
import net.momirealms.sparrow.ui.pane.page.Scroll;
import net.momirealms.sparrow.ui.pane.page.Tab;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.visual.PaneVisual;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Pane permits AbstractPane {

    /**
     * 按尺寸建一个 Builder, 出来的是一块空 Pane.
     *
     * @param size Pane 尺寸
     * @return 普通 Pane Builder
     */
    @NotNull
    static Builder<NormalPane, ?> builder(@NotNull PaneSize size) {
        return NormalPane.builder(size);
    }

    /**
     * 按宽高建一个 Builder.
     *
     * @param width Pane 宽度
     * @param height Pane 高度
     * @return 普通 Pane Builder
     */
    @NotNull
    static Builder<NormalPane, ?> builder(int width, int height) {
        return builder(new PaneSize(width, height));
    }

    /**
     * 用现成的布局建一个 Builder, 标志符的槽位这时就已经定好了.
     *
     * @param structure Pane 布局
     * @return 普通 Pane Builder
     */
    @NotNull
    static Builder<NormalPane, ?> builder(@NotNull Structure structure) {
        return NormalPane.builder(structure);
    }

    /**
     * 先把模板行解析成布局, 再开 Builder.
     *
     * @param rows 布局模板行
     * @return 普通 Pane Builder
     */
    @NotNull
    static Builder<NormalPane, ?> builder(String @NotNull ... rows) {
        return NormalPane.builder(Structure.of(rows));
    }

    /**
     * 把一段连续文本按宽高解析成布局, 再开 Builder.
     *
     * @param width Pane 宽度
     * @param height Pane 高度
     * @param flatData 连续布局文本
     * @return 普通 Pane Builder
     */
    @NotNull
    static Builder<NormalPane, ?> builder(int width, int height, @NotNull String flatData) {
        return builder(Structure.of(new PaneSize(width, height), flatData));
    }

    /**
     * 建一个全空的 Pane.
     *
     * @param width Pane 宽度
     * @param height Pane 高度
     * @return 空 Pane
     */
    @NotNull
    static NormalPane empty(int width, int height) {
        return NormalPane.empty(new PaneSize(width, height));
    }

    /**
     * 建一个全空的 Pane.
     *
     * @param size Pane 尺寸
     * @return 空 Pane
     */
    @NotNull
    static NormalPane empty(@NotNull PaneSize size) {
        return NormalPane.empty(size);
    }

    /**
     * 建一个每格都摆着同一个 Item 的 Pane.
     * <p>所有槽位拿到的是同一个 Item 实例.
     *
     * @param width Pane 宽度
     * @param height Pane 高度
     * @param item 每个槽位显示的 Item
     * @return 填满 Item 的 Pane
     */
    @NotNull
    static NormalPane filled(int width, int height, @NotNull Item item) {
        NormalPane pane = empty(width, height);
        pane.fill(item);
        return pane;
    }

    /**
     * 用现成的布局建一个全空的 Pane.
     *
     * @param structure Pane 布局
     * @return 空 Pane
     */
    @NotNull
    static NormalPane of(@NotNull Structure structure) {
        return NormalPane.from(structure);
    }

    /**
     * 只有一个槽位的 Pane, 里面就放这个 Item.
     *
     * @param item 唯一槽位显示的 Item
     * @return 单槽位 Pane
     */
    @NotNull
    static NormalPane single(@NotNull Item item) {
        return NormalPane.builder(new PaneSize(1, 1))
                .addModifier(pane -> pane.setItem(0, item))
                .build();
    }

    /**
     * 本 Pane 的宽高.
     *
     * @return Pane 尺寸
     */
    @NotNull
    PaneSize size();

    /**
     * 本 Pane 的槽位布局, 尺寸和标志符都从它来.
     *
     * @return Pane 布局
     */
    @NotNull
    Structure structure();

    default int width() {
        return this.size().width();
    }

    default int height() {
        return this.size().height();
    }

    default int area() {
        return this.size().area();
    }

    /**
     * 这一格现在放着什么.
     *
     * @param slot 槽位编号
     * @return 槽位元素
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    @NotNull
    Element element(int slot);

    @NotNull
    default Element element(int x, int y) {
        return this.element(this.size().indexOf(x, y));
    }

    /**
     * 把全部槽位元素复制成一份数组交给调用方.
     *
     * @return 元素数组副本
     */
    Element @NotNull [] elements();

    default boolean hasElement(int slot) {
        return this.element(slot) != Element.Empty.INSTANCE;
    }

    default boolean hasElement(int x, int y) {
        return this.hasElement(this.size().indexOf(x, y));
    }

    /**
     * 这一格是 Item 元素时给出那个 Item, 别的元素(空的, 连向子 Pane 的, 连向 Inventory 的)一律给 null.
     *
     * @param slot 槽位编号
     * @return Item; 这一格不是 Item 时是 null
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    @Nullable
    default Item item(int slot) {
        return this.element(slot) instanceof Element.Item(var item) ? item : null;
    }

    @Nullable
    default Item item(int x, int y) {
        return this.item(this.size().indexOf(x, y));
    }

    /**
     * 这一格属于哪个标志符.
     *
     * @param slot 槽位编号
     * @return 标志符; 这一格在布局里没有名字时是 null
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    @Nullable
    default String identifierAt(int slot) {
        return this.structure().identifierAt(slot);
    }

    @Nullable
    default String identifierAt(int x, int y) {
        return this.structure().identifierAt(this.size().indexOf(x, y));
    }

    /**
     * 这一格是不是这个标志符的.
     *
     * @param slot 槽位编号
     * @param identifier 标志符
     * @return 属于这个标志符时为 true
     */
    default boolean isTagged(int slot, @NotNull String identifier) {
        return identifier.equals(this.identifierAt(slot));
    }

    default boolean isTagged(int x, int y, @NotNull String identifier) {
        return this.isTagged(this.size().indexOf(x, y), identifier);
    }

    /**
     * 这个标志符占的全部槽位.
     *
     * @param identifier 标志符
     * @return 对应槽位选择
     * @throws IllegalArgumentException 标志符是空串, 或者布局里没有它时
     */
    @NotNull
    default SlotSequence slots(@NotNull String identifier) {
        return this.structure().slots(identifier);
    }

    /**
     * 把几个标志符的槽位合到一起, 再按 Pattern 决定留哪些, 什么顺序.
     *
     * @param pattern 槽位选择方式
     * @param identifiers 要合并的标志符
     * @return 筛过也排过的槽位选择
     * @throws IllegalArgumentException 一个标志符都没给, 或者布局里没有它时
     */
    @NotNull
    default SlotSequence slots(@NotNull SlotPattern pattern, String @NotNull ... identifiers) {
        return this.structure().slots(pattern, identifiers);
    }

    /**
     * 把这一格换成新的元素.
     * <p>传的还是原来那个实例就当没变, 订阅者不会被叫醒; 想让同一份内容重发一次, 用 {@link #dirty(int)}.
     *
     * @param slot 槽位编号
     * @param element 新元素
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    void setElement(int slot, @NotNull Element element);

    default void setElement(int x, int y, @NotNull Element element) {
        this.setElement(this.size().indexOf(x, y), element);
    }

    /**
     * 把这个标志符占的槽位全换成同一个元素.
     *
     * @param identifier 标志符
     * @param element 新元素
     */
    default void setElement(@NotNull String identifier, @NotNull Element element) {
        this.setElements(this.slots(identifier), ElementSupplier.fixed(element), true);
    }

    default void setElement(char identifier, @NotNull Element element) {
        this.setElement(String.valueOf(identifier), element);
    }

    /**
     * 把这个标志符的槽位交给 supplier 一格一格生成.
     *
     * @param identifier 标志符
     * @param supplier 元素生成器
     */
    default void setElement(@NotNull String identifier, @NotNull ElementSupplier supplier) {
        this.setElements(this.slots(identifier), supplier, true);
    }

    /**
     * 给选中的槽位生成元素并写进去, 全部生成成功才动 Pane.
     * <p>{@code replaceExisting} 为 false 时只填空槽, 已经有内容的格跳过; supplier 中途抛异常的话 Pane 一个字都不变.
     * 生成出来的元素和格子里现在是同一个实例时, 那一格不算变化, 不通知订阅者.
     *
     * @param slots 要写入的槽位选择, <strong>必须属于本 Pane</strong>
     * @param supplier 元素生成器
     * @param replaceExisting 是否覆盖已有内容
     * @throws IllegalArgumentException 槽位选择属于别的 Pane 时
     */
    void setElements(@NotNull SlotSequence slots, @NotNull ElementSupplier supplier, boolean replaceExisting);

    /**
     * 让这一格显示这个 Item.
     *
     * @param slot 槽位编号
     * @param item Item
     */
    default void setItem(int slot, @NotNull Item item) {
        this.setElement(slot, new Element.Item(item));
    }

    default void setItem(int x, int y, @NotNull Item item) {
        this.setItem(this.size().indexOf(x, y), item);
    }

    /**
     * 这个标志符占的槽位全都显示同一个 Item.
     *
     * @param identifier 标志符
     * @param item Item
     */
    default void setItem(@NotNull String identifier, @NotNull Item item) {
        this.setElement(identifier, new Element.Item(item));
    }

    /**
     * 这个标志符的每一格各取一次 supplier.
     *
     * @param identifier 标志符
     * @param supplier Item 来源
     */
    default void setItem(@NotNull String identifier, @NotNull Supplier<? extends Item> supplier) {
        this.setElements(this.slots(identifier), ElementSupplier.items(supplier), true);
    }

    /**
     * 把这一格接到子 Pane 的某一格上.
     *
     * @param slot 当前 Pane 槽位
     * @param pane 子 Pane
     * @param paneSlot 子 Pane 槽位
     * @throws IndexOutOfBoundsException 任一槽号越界时
     */
    default void setPane(int slot, @NotNull Pane pane, int paneSlot) {
        this.setElement(slot, new Element.PaneLink(pane, paneSlot));
    }

    /**
     * 把这个标志符的区域按二维形状接到子 Pane 的左上角.
     *
     * @param identifier 标志符
     * @param pane 子 Pane
     */
    default void setPane(@NotNull String identifier, @NotNull Pane pane) {
        this.setPane(identifier, pane, 0, 0);
    }

    default void setPane(char identifier, @NotNull Pane pane) {
        this.setPane(String.valueOf(identifier), pane);
    }

    /**
     * 把这个标志符的区域按二维形状接到子 Pane 的指定偏移处.
     *
     * @param identifier 标志符
     * @param pane 子 Pane
     * @param offsetX 子 Pane 横向偏移
     * @param offsetY 子 Pane 纵向偏移
     * @throws IndexOutOfBoundsException 选中区域探出子 Pane 时
     */
    default void setPane(@NotNull String identifier, @NotNull Pane pane, int offsetX, int offsetY) {
        this.setElements(
                this.slots(identifier),
                ElementSupplier.pane(pane, offsetX, offsetY),
                true
        );
    }

    /**
     * 按给的顺序把元素塞进最靠前的空槽位.
     * <p>空元素跳过; 空位不够时放不下的尾部元素直接丢掉, 前面的照样摆好.
     *
     * @param elements 要添加的元素
     * @throws NullPointerException 数组里有 null 时
     */
    void addElements(Element @NotNull ... elements);

    /**
     * 按给的顺序把 Item 塞进最靠前的空槽位.
     * <p>空位不够时放不下的尾部 Item 直接丢掉.
     *
     * @param items 要添加的 Item
     * @throws NullPointerException 数组里有 null 时
     */
    void addItems(Item @NotNull ... items);

    /**
     * 让选中的槽位重新走一遍显示, 就算格子里还是原来那个元素.
     *
     * @param slots 需要刷新的槽位选择
     * @throws IllegalArgumentException 槽位选择属于别的 Pane 时
     */
    void dirty(@NotNull SlotSequence slots);

    /**
     * 让这一格重新显示一遍.
     *
     * @param slot 槽位编号
     */
    default void dirty(int slot) {
        this.dirty(SlotSequence.of(this.size(), slot));
    }

    default void dirty(int x, int y) {
        this.dirty(this.size().indexOf(x, y));
    }

    default void dirty(@NotNull String identifier) {
        this.dirty(this.slots(identifier));
    }

    /**
     * 整个 Pane 都换成这一个元素.
     *
     * @param element 槽位元素
     * @param replaceExisting 是否覆盖已有内容
     */
    default void fillElement(@NotNull Element element, boolean replaceExisting) {
        this.setElements(
                SlotSequence.all(this.size()),
                ElementSupplier.fixed(element),
                replaceExisting
        );
    }

    default void fillElement(@NotNull Element element) {
        this.fillElement(element, true);
    }

    /**
     * 整个 Pane 都摆同一个 Item.
     *
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
     */
    default void fill(@NotNull Item item, boolean replaceExisting) {
        this.fillElement(new Element.Item(item), replaceExisting);
    }

    default void fill(@NotNull Item item) {
        this.fill(item, true);
    }

    /**
     * 一段连续的槽位全摆同一个 Item.
     *
     * @param startInclusive 起始槽位, 包含
     * @param endExclusive 结束槽位, 不包含
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
     * @throws IndexOutOfBoundsException 范围超出 Pane 时
     */
    default void fill(int startInclusive, int endExclusive, @NotNull Item item, boolean replaceExisting) {
        this.setElements(
                SlotSequence.range(this.size(), startInclusive, endExclusive),
                ElementSupplier.fixed(new Element.Item(item)),
                replaceExisting
        );
    }

    default void fill(int startInclusive, int endExclusive, @NotNull Item item) {
        this.fill(startInclusive, endExclusive, item, true);
    }

    /**
     * 一整行都摆同一个 Item.
     *
     * @param row 行号
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
     * @throws IndexOutOfBoundsException 行号越界时
     */
    default void fillRow(int row, @NotNull Item item, boolean replaceExisting) {
        this.setElements(
                SlotSequence.row(this.size(), row),
                ElementSupplier.fixed(new Element.Item(item)),
                replaceExisting
        );
    }

    default void fillRow(int row, @NotNull Item item) {
        this.fillRow(row, item, true);
    }

    /**
     * 一整列都摆同一个 Item.
     *
     * @param column 列号
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
     * @throws IndexOutOfBoundsException 列号越界时
     */
    default void fillColumn(int column, @NotNull Item item, boolean replaceExisting) {
        this.setElements(
                SlotSequence.column(this.size(), column),
                ElementSupplier.fixed(new Element.Item(item)),
                replaceExisting
        );
    }

    default void fillColumn(int column, @NotNull Item item) {
        this.fillColumn(column, item, true);
    }

    /**
     * Pane 四周那一圈都摆同一个 Item.
     *
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
     */
    default void fillBorders(@NotNull Item item, boolean replaceExisting) {
        this.setElements(
                SlotSequence.borders(this.size()),
                ElementSupplier.fixed(new Element.Item(item)),
                replaceExisting
        );
    }

    default void fillBorders(@NotNull Item item) {
        this.fillBorders(item, true);
    }

    /**
     * 一块矩形区域里全摆同一个 Item.
     *
     * @param x 矩形左上角在第几列
     * @param y 矩形左上角在第几行
     * @param width 矩形宽度
     * @param height 矩形高度
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
     * @throws IllegalArgumentException 矩形的宽或高不是正数时
     * @throws IndexOutOfBoundsException 矩形探出 Pane 时
     */
    default void fillRectangle(int x, int y, int width, int height, @NotNull Item item, boolean replaceExisting) {
        this.setElements(
                SlotSequence.rectangle(this.size(), x, y, width, height),
                ElementSupplier.fixed(new Element.Item(item)),
                replaceExisting
        );
    }

    default void fillRectangle(int x, int y, int width, int height, @NotNull Item item) {
        this.fillRectangle(x, y, width, height, item, true);
    }

    /**
     * 把子 Pane 整个铺进一块矩形区域, 区域大小按子 Pane 自己的尺寸算.
     *
     * @param x 矩形左上角在第几列
     * @param y 矩形左上角在第几行
     * @param child 子 Pane
     * @param replaceExisting 是否覆盖已有内容
     * @throws IllegalArgumentException 子 Pane 的宽或高是 0 时
     * @throws IndexOutOfBoundsException 子 Pane 放不进指定位置时
     */
    default void fillRectangle(int x, int y, @NotNull Pane child, boolean replaceExisting) {
        this.setElements(
                SlotSequence.rectangle(this.size(), x, y, child.width(), child.height()),
                ElementSupplier.pane(child),
                replaceExisting
        );
    }

    default void fillRectangle(int x, int y, @NotNull Pane child) {
        this.fillRectangle(x, y, child, true);
    }

    /**
     * 本 Pane 的视觉配置, 对显示它的每个 Window 都生效.
     *
     * @return 视觉配置
     */
    @NotNull
    PaneVisual visual();

    /**
     * 现在这一层的全局视觉映射.
     *
     * @return 全局视觉映射; 没设时为 null
     */
    @Nullable
    default Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.visual().visualizerProvider();
    }

    /**
     * 换掉全局视觉映射, 输入和层级的约定见 {@link PaneVisual}.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示不参与这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.visual().setVisualizerProvider(visualizerProvider);
    }

    /**
     * 换掉全局视觉映射, 顺带给它配一个占位.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示不参与这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示终点连接 Inventory 时显示该槽真实内容, 其余终点显示空
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        this.visual().setVisualizerProvider(visualizerProvider, placeholder);
    }

    /**
     * 用直接返回 ItemStack 的映射当全局这一层.
     *
     * @param visualizer 新的全局物品映射, {@code null} 表示不参与这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visual().setVisualizerItem(visualizer);
    }

    /**
     * 这一格自己的视觉映射, 不含全局那一层.
     *
     * @param slot Pane 槽位
     * @return 逐槽视觉映射; 没设时为 null
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    @Nullable
    default Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider(int slot) {
        return this.visual().visualizerProvider(slot);
    }

    /**
     * 设置这一格的视觉映射, 它盖在全局映射上面.
     *
     * @param slot Pane 槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    default void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.visual().setVisualizerProvider(slot, visualizerProvider);
    }

    /**
     * 设置这一格的视觉映射, 顺带给它配一个占位.
     *
     * @param slot Pane 槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示终点连接 Inventory 时显示该槽真实内容, 其余终点显示空
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    default void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        this.visual().setVisualizerProvider(slot, visualizerProvider, placeholder);
    }

    /**
     * 用直接返回 ItemStack 的映射当这一格的视觉映射.
     * <p>映射返回 null 就是放行, 返回空 ItemStack 则是把它盖成空视觉.
     *
     * @param slot Pane 槽位
     * @param visualizer 新的逐槽物品映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    default void setVisualizerItem(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visual().setVisualizerItem(slot, visualizer);
    }


    /**
     * 空槽位显示什么.
     *
     * @return Pane 背景; 没设时为 null
     */
    @Nullable
    ItemProvider background();

    /**
     * 换掉空槽位的背景.
     *
     * @param background Pane 背景, null 表示清除背景
     */
    void setBackground(@Nullable ItemProvider background);

    /**
     * 用 Bukkit ItemStack 当背景.
     *
     * @param background Pane 背景
     */
    default void setBackgroundItem(@NotNull ItemStack background) {
        this.setBackground(ItemProvider.constant(background));
    }

    /**
     * 这个 Pane 冻没冻.
     *
     * @return 冻住时为 true
     */
    boolean frozen();

    /**
     * 冻住或者解冻这个 Pane.
     * <p>冻住只是不让玩家点击落到它身上, 显示和刷新照常.
     *
     * @param frozen true 表示禁止交互
     */
    void setFrozen(boolean frozen);

    /**
     * 额外声明一个 Inventory 参与本 Pane 所在 Window 的点击语义.
     * <p>没在任何槽位露面的 Inventory 也照样进快速转移和双击收集的候选集.
     * <p><strong>还得打开该 Inventory 的 {@link SparrowInventory#includeObscuredSlots(boolean)}.</strong>
     * <p><strong>只有本 Pane 在当前显示路径上时, 这条声明才起作用.</strong>
     *
     * @param inventory 要额外带进参与集的 Inventory
     */
    void linkInventory(@NotNull SparrowInventory inventory);

    /**
     * 撤销先前那次逐个声明.
     * <p>经 {@link #linkInventory(InventorySequence)} 声明进来的成员不归它管, 要移除得动那个序列.
     *
     * @param inventory 要取消的 Inventory
     * @return 这个 Inventory 本来就声明过时为 true
     */
    boolean unlinkInventory(@NotNull SparrowInventory inventory);

    /**
     * 逐个声明进来的那些 Inventory, 按声明顺序.
     * <p>不含经 {@link #linkInventory(InventorySequence)} 声明进来的序列成员.
     *
     * @return 按声明顺序排列的不可变快照
     */
    @NotNull
    List<SparrowInventory> linkedInventories();

    /**
     * 额外声明一整条序列参与本 Pane 所在 Window 的点击语义.
     * <p>每次规划都重新读它的成员, 序列自己增减成员之后不用再声明一遍.
     *
     * @param sequence 要额外带进参与集的序列
     */
    void linkInventory(@NotNull InventorySequence sequence);

    /**
     * 撤销先前那次整条序列的声明.
     *
     * @param sequence 要取消的序列
     * @return 这个序列本来就声明过时为 true
     */
    boolean unlinkInventory(@NotNull InventorySequence sequence);

    /**
     * 整条声明进来的那些序列, 按声明顺序.
     * <p>逐个声明的 Inventory 在 {@link #linkedInventories()} 那边.
     *
     * @return 按声明顺序排列的不可变快照
     */
    @NotNull
    Set<InventorySequence> linkedSequences();

    /**
     * 本 Pane 带进参与集的所有序列, 是上面两份声明的并集.
     *
     * @return 按声明顺序排列的不可变快照
     */
    @NotNull
    @ApiStatus.Internal
    Set<InventorySequence> participatingSequences();

    /**
     * 订阅这一格的更新, 顺带拿到订阅建立那一刻的元素和冻结状态.
     * <p>建的时候不会回调: 之后元素被换掉, 被 {@link #dirty(int)} 标脏, 或者冻结状态变了才叫 observer.
     *
     * @param slot 槽位编号
     * @param observer 槽位更新观察者
     * @return 订阅和当前状态
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    @NotNull
    PaneSlotAttachment attach(int slot, @NotNull Observer<? super Pane> observer);

    /**
     * 让 Signal 每次失效都回调一次.
     * <p>不会当场补一趟当前值, 第一次回调要等下一次标脏.
     * <p>Signal 通过订阅节点弱持有本 Pane, 而绑定挂在本对象身上: Pane 被回收时一起消失,
     * callback 里捕获的东西也跟着释放, 使用方不用专门记着退订.
     *
     * @param signal 数据源
     * @param callback 失效回调
     * @return 订阅凭证, 可用于提前解绑
     */
    @NotNull
    Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super Pane> callback);

    /**
     * 让选中的槽位一直跟着一个序列走, 序列第 n 项落到第 n 个槽位.
     * <p>建的时候先当场算一轮, 之后每次序列失效都在 Paper 全局异步调度器上重算, 所以序列的派生函数和
     * {@code toElement} 都只能读异步域里访问安全的数据.
     *
     * @param <T> 序列元素类型
     * @param slots 这次投影负责的槽位, <strong>必须属于本 Pane</strong>
     * @param source 序列来源
     * @param toElement 把序列里的一条数据变成 Element, <strong>不能返回 null</strong>
     * @return 投影, 可用来提前停止
     * @throws IllegalArgumentException 槽位选择属于别的 Pane 时
     */
    @NotNull
    default <T> SlotProjection project(
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends T>> source,
            @NotNull Function<? super T, ? extends Element> toElement
    ) {
        return SlotProjection.attach(this, slots, source, toElement);
    }

    /**
     * 同 {@link #project(SlotSequence, Signal, Function)}, 但后续失效改到指定的 executor 上重算.
     * <p>第一轮还是在调用线程同步算. {@code source} 和 {@code toElement} 两个线程上都得能安全跑.
     *
     * @param <T> 序列元素类型
     * @param slots 这次投影负责的槽位, <strong>必须属于本 Pane</strong>
     * @param source 序列来源
     * @param toElement 把序列里的一条数据变成 Element, <strong>不能返回 null</strong>
     * @param executor 执行求值的执行器
     * @return 投影, 可用来提前停止
     * @throws IllegalArgumentException 槽位选择属于别的 Pane 时
     */
    @NotNull
    default <T> SlotProjection project(
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends T>> source,
            @NotNull Function<? super T, ? extends Element> toElement,
            @NotNull Executor executor
    ) {
        return SlotProjection.attach(this, slots, source, toElement, executor);
    }

    /**
     * 序列里已经是 Element 时用这个, 省一个转换函数.
     *
     * @param slots 这次投影负责的槽位, <strong>必须属于本 Pane</strong>
     * @param source 序列来源
     * @return 投影, 可用来提前停止
     * @throws IllegalArgumentException 槽位选择属于别的 Pane 时
     */
    @NotNull
    default SlotProjection projectElements(
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends Element>> source
    ) {
        return SlotProjection.attachElements(this, slots, source);
    }

    /**
     * 同 {@link #projectElements(SlotSequence, Signal)}, 但后续失效改到指定的 executor 上重算.
     * <p>第一轮还是在调用线程同步算.
     *
     * @param slots 这次投影负责的槽位, <strong>必须属于本 Pane</strong>
     * @param source 序列来源
     * @param executor 执行求值的执行器
     * @return 投影, 可用来提前停止
     * @throws IllegalArgumentException 槽位选择属于别的 Pane 时
     */
    @NotNull
    default SlotProjection projectElements(
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends Element>> source,
            @NotNull Executor executor
    ) {
        return SlotProjection.attachElements(this, slots, source, executor);
    }

    /**
     * 按布局里的标志符声明内容, 最后 build 出一个 Pane.
     * <p>同一个标志符只认最后一次声明, 静态内容, 投影和标签组这三者互相顶掉.
     * build 的时候先摆静态内容, 再铺投影和标签组, 最后跑 modifiers; 标志符在布局里不存在的话, 声明那一刻就抛.
     *
     * @param <G> 构建出的 Pane 类型
     * @param <B> Builder 自身类型
     */
    interface Builder<G extends Pane, B extends Builder<G, B>> {

        /**
         * Builder 正在用的那份布局.
         *
         * @return Pane 布局
         */
        @NotNull
        Structure structure();

        /**
         * 这个标志符的槽位交给 supplier 一格一格生成.
         *
         * @param identifier 标志符
         * @param supplier 元素生成器
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull ElementSupplier supplier);

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param identifier 标志符, 只写一个字符
         * @param supplier 元素生成器
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull ElementSupplier supplier);

        /**
         * 这个标志符的槽位全放同一个元素.
         *
         * @param identifier 标志符
         * @param element 槽位元素
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Element element);

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param identifier 标志符, 只写一个字符
         * @param element 槽位元素
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Element element);

        /**
         * 这个标志符的槽位全摆同一个 Item.
         *
         * @param identifier 标志符
         * @param item Item
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Item item);

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param identifier 标志符, 只写一个字符
         * @param item Item
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Item item);

        /**
         * 这个标志符每格各 build 一次这个 ItemBuilder.
         *
         * @param identifier 标志符
         * @param itemBuilder Item Builder
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull ItemBuilder itemBuilder);

        /**
         * 这个标志符的槽位共用这一个 ItemProvider.
         *
         * @param identifier 标志符
         * @param provider Item 内容来源
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull ItemProvider provider);

        /**
         * 这个标志符的槽位全显示这个 ItemStack.
         *
         * @param identifier 标志符
         * @param itemStack Bukkit ItemStack
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull ItemStack itemStack);

        /**
         * 这个标志符每格各取一次 supplier.
         *
         * @param identifier 标志符
         * @param itemSupplier Item 来源
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Supplier<? extends Item> itemSupplier);

        /**
         * 这个标志符每格各取一次 elementSupplier.
         *
         * @param identifier 标志符
         * @param elementSupplier 槽位元素来源
         * @return 当前 Builder
         */
        @NotNull
        B addIngredientElementSupplier(@NotNull String identifier, @NotNull Supplier<? extends Element> elementSupplier);

        /**
         * 把这个标志符的槽位按出现顺序循环接到 Inventory 上.
         * <p>标志符第 n 次出现的槽位接的是 Inventory 第 {@code n % inventory.size()} 格.
         * Inventory 是空的时候所有槽位都是空的.
         *
         * @param identifier 标志符
         * @param inventory 连接的 Inventory
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull SparrowInventory inventory);

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param identifier 标志符, 只写一个字符
         * @param inventory 连接的 Inventory
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull SparrowInventory inventory);

        /**
         * 让这个标志符的槽位一直跟着序列走, 序列第 n 项落到该标志符第 n 次出现的槽位.
         * <p>build 的时候在调用线程先算一轮, 之后每次失效在 Paper 全局异步调度器上重算.
         * 序列本身已经是 Element 时改用 {@code addModifier(pane -> pane.projectElements(pane.slots(identifier), ...))}.
         *
         * @param <T> 序列元素类型
         * @param identifier 标志符
         * @param source 序列来源
         * @param toElement 把序列里的一条数据变成 Element, <strong>不能返回 null</strong>
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                @NotNull String identifier,
                @NotNull Signal<? extends List<? extends T>> source,
                @NotNull Function<? super T, ? extends Element> toElement
        );

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param <T> 序列元素类型
         * @param identifier 标志符, 只写一个字符
         * @param source 序列来源
         * @param toElement 把序列里的一条数据变成一个 Element, 不得返回 {@code null}
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                char identifier,
                @NotNull Signal<? extends List<? extends T>> source,
                @NotNull Function<? super T, ? extends Element> toElement
        );

        /**
         * 同 {@link #addIngredient(String, Signal, Function)}, 但后续失效改到指定的 executor 上重算.
         * <p>第一轮还是在 build 的调用线程上算.
         *
         * @param <T> 序列元素类型
         * @param identifier 标志符
         * @param source 序列来源
         * @param toElement 把序列里的一条数据变成一个 Element, 不得返回 {@code null}
         * @param executor 执行求值的执行器
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                @NotNull String identifier,
                @NotNull Signal<? extends List<? extends T>> source,
                @NotNull Function<? super T, ? extends Element> toElement,
                @NotNull Executor executor
        );

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param <T> 序列元素类型
         * @param identifier 标志符, 只写一个字符
         * @param source 序列来源
         * @param toElement 把序列里的一条数据变成一个 Element, 不得返回 {@code null}
         * @param executor 执行求值的执行器
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                char identifier,
                @NotNull Signal<? extends List<? extends T>> source,
                @NotNull Function<? super T, ? extends Element> toElement,
                @NotNull Executor executor
        );

        /**
         * 让这个标志符的槽位一直跟着翻页的当前页走, 当前页第 n 条落到该标志符第 n 次出现的槽位.
         *
         * @param <T> 当前页元素类型
         * @param identifier 标志符
         * @param page 翻页
         * @param toElement 把当前页里的一条数据变成 Element, <strong>不能返回 null</strong>
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                @NotNull String identifier,
                @NotNull Page<T> page,
                @NotNull Function<? super T, ? extends Element> toElement
        );

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param <T> 当前页元素类型
         * @param identifier 标志符, 只写一个字符
         * @param page 翻页
         * @param toElement 把当前页里的一条数据变成一个 Element, 不得返回 {@code null}
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                char identifier,
                @NotNull Page<T> page,
                @NotNull Function<? super T, ? extends Element> toElement
        );

        /**
         * 同 {@link #addIngredient(String, Page, Function)}, 但后续失效改到指定的 executor 上重算.
         * <p>第一轮还是在 build 的调用线程上算.
         *
         * @param <T> 当前页元素类型
         * @param identifier 标志符
         * @param page 翻页
         * @param toElement 把当前页里的一条数据变成一个 Element, 不得返回 {@code null}
         * @param executor 执行求值的执行器
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                @NotNull String identifier,
                @NotNull Page<T> page,
                @NotNull Function<? super T, ? extends Element> toElement,
                @NotNull Executor executor
        );

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param <T> 当前页元素类型
         * @param identifier 标志符, 只写一个字符
         * @param page 翻页
         * @param toElement 把当前页里的一条数据变成一个 Element, 不得返回 {@code null}
         * @param executor 执行求值的执行器
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                char identifier,
                @NotNull Page<T> page,
                @NotNull Function<? super T, ? extends Element> toElement,
                @NotNull Executor executor
        );

        /**
         * 同 {@link #addIngredient(String, Page, Function)}, 但页里本来就是 Item, 不用给转换函数.
         *
         * @param identifier 标志符
         * @param page 内容是 Item 的翻页
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Page<? extends Item> page);

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param identifier 标志符, 只写一个字符
         * @param page 内容是 Item 的翻页
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Page<? extends Item> page);

        /**
         * 让这个标志符的槽位一直跟着滚动的当前屏走, 这一屏第 n 条落到该标志符第 n 次出现的槽位.
         * <p>槽位顺序跟着滚动方向走: 竖滚按行, 横滚按列.
         *
         * @param <T> 当前屏元素类型
         * @param identifier 标志符
         * @param scroll 滚动
         * @param toElement 把这一屏里的一条数据变成 Element, <strong>不能返回 null</strong>
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                @NotNull String identifier,
                @NotNull Scroll<T> scroll,
                @NotNull Function<? super T, ? extends Element> toElement
        );

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param <T> 当前屏元素类型
         * @param identifier 标志符, 只写一个字符
         * @param scroll 滚动
         * @param toElement 把这一屏里的一条数据变成一个 Element, 不得返回 {@code null}
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                char identifier,
                @NotNull Scroll<T> scroll,
                @NotNull Function<? super T, ? extends Element> toElement
        );

        /**
         * 同 {@link #addIngredient(String, Scroll, Function)}, 但后续失效改到指定的 executor 上重算.
         * <p>第一轮还是在 build 的调用线程上算.
         *
         * @param <T> 当前屏元素类型
         * @param identifier 标志符
         * @param scroll 滚动
         * @param toElement 把这一屏里的一条数据变成一个 Element, 不得返回 {@code null}
         * @param executor 执行求值的执行器
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                @NotNull String identifier,
                @NotNull Scroll<T> scroll,
                @NotNull Function<? super T, ? extends Element> toElement,
                @NotNull Executor executor
        );

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param <T> 当前屏元素类型
         * @param identifier 标志符, 只写一个字符
         * @param scroll 滚动
         * @param toElement 把这一屏里的一条数据变成一个 Element, 不得返回 {@code null}
         * @param executor 执行求值的执行器
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                char identifier,
                @NotNull Scroll<T> scroll,
                @NotNull Function<? super T, ? extends Element> toElement,
                @NotNull Executor executor
        );

        /**
         * 同 {@link #addIngredient(String, Scroll, Function)}, 但屏里本来就是 Item, 不用给转换函数.
         *
         * @param identifier 标志符
         * @param scroll 内容是 Item 的滚动
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Scroll<? extends Item> scroll);

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param identifier 标志符, 只写一个字符
         * @param scroll 内容是 Item 的滚动
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Scroll<? extends Item> scroll);

        /**
         * 让这个标志符的槽位一直显示标签组当前选中的子 Pane.
         * <p>区域按二维形状接过去, 换标签时整片重铺; 子 Pane 盖不到的槽位补空.
         * 切到已经选中的那个标签不会有任何动静.
         *
         * @param identifier 标志符
         * @param tab 标签组
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Tab<?> tab);

        /**
         * 标志符写单个字符的简写入口.
         *
         * @param identifier 标志符, 只写一个字符
         * @param tab 标签组
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Tab<?> tab);

        /**
         * 把这个标志符的区域按二维形状接到子 Pane 的左上角.
         *
         * @param identifier 标志符
         * @param pane 子 Pane
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Pane pane);

        /**
         * 同 {@link #addIngredient(String, Pane)}, 但挪到子 Pane 里的指定偏移处.
         *
         * @param identifier 标志符
         * @param pane 子 Pane
         * @param offsetX 子 Pane 横向偏移
         * @param offsetY 子 Pane 纵向偏移
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Pane pane, int offsetX, int offsetY);

        /**
         * 换掉空槽位的背景.
         *
         * @param background Pane 背景, null 表示清除背景
         * @return 当前 Builder
         */
        @NotNull
        B setBackground(@Nullable ItemProvider background);

        /**
         * 用 Bukkit ItemStack 当背景.
         *
         * @param background 背景 ItemStack
         * @return 当前 Builder
         */
        @NotNull
        B setBackground(@NotNull ItemStack background);

        /**
         * 冻住或者解冻建出来的这个 Pane, 语义见 {@link Pane#setFrozen(boolean)}.
         *
         * @param frozen true 表示禁止交互
         * @return 当前 Builder
         */
        @NotNull
        B setFrozen(boolean frozen);

        /**
         * 额外声明一个 Inventory 参与进来, 语义见 {@link Pane#linkInventory(SparrowInventory)}.
         *
         * @param inventory 要额外带进参与集的 Inventory
         * @return 当前 Builder
         */
        @NotNull
        B linkInventory(@NotNull SparrowInventory inventory);

        /**
         * 加一个修改操作, Pane 建好之后按加入顺序跑.
         *
         * @param modifier Pane 修改操作
         * @return 当前 Builder
         */
        @NotNull
        B addModifier(@NotNull Consumer<? super G> modifier);

        /**
         * 把这些修改操作整批换成新的.
         *
         * @param modifiers Pane 修改操作
         * @return 当前 Builder
         */
        @NotNull
        B setModifiers(@NotNull List<? extends Consumer<? super G>> modifiers);

        /**
         * 复制一份 Builder, 之后两边各改各的互不影响.
         *
         * @return Builder 副本
         */
        @NotNull
        B copy();

        /**
         * 按现在的配置造一个 Pane.
         * <p>先摆静态内容, 再关联声明过的 Inventory, 然后铺投影和标签组, 最后跑修改器.
         *
         * @return 新 Pane
         * @throws IllegalStateException 静态内容生成失败时
         */
        @NotNull
        G build();
    }
}
