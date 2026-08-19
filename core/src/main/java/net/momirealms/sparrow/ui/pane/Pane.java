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
     * 为指定尺寸创建普通 Pane Builder.
     *
     * @param size Pane 尺寸
     * @return 普通 Pane Builder
     */
    @NotNull
    static Builder<NormalPane, ?> builder(@NotNull PaneSize size) {
        return NormalPane.builder(size);
    }

    /**
     * 为指定宽高创建普通 Pane Builder.
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
     * 使用已有布局创建普通 Pane Builder.
     *
     * @param structure Pane 布局
     * @return 普通 Pane Builder
     */
    @NotNull
    static Builder<NormalPane, ?> builder(@NotNull Structure structure) {
        return NormalPane.builder(structure);
    }

    /**
     * 先解析多行布局, 再创建普通 Pane Builder.
     *
     * @param rows 布局模板行
     * @return 普通 Pane Builder
     */
    @NotNull
    static Builder<NormalPane, ?> builder(String @NotNull ... rows) {
        return NormalPane.builder(Structure.of(rows));
    }

    /**
     * 先解析连续布局文本, 再创建普通 Pane Builder.
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
     * 创建所有槽位都为空的普通 Pane.
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
     * 创建所有槽位都为空的普通 Pane.
     *
     * @param size Pane 尺寸
     * @return 空 Pane
     */
    @NotNull
    static NormalPane empty(@NotNull PaneSize size) {
        return NormalPane.empty(size);
    }

    /**
     * 创建每个槽位都显示同一 Item 的普通 Pane.
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
     * 使用已有布局创建所有槽位都为空的普通 Pane.
     *
     * @param structure Pane 布局
     * @return 空 Pane
     */
    @NotNull
    static NormalPane of(@NotNull Structure structure) {
        return NormalPane.from(structure);
    }

    /**
     * 创建只有一个槽位的 Pane.
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
     * 返回 Pane 的宽高.
     *
     * @return Pane 尺寸
     */
    @NotNull
    PaneSize size();

    /**
     * 返回 Pane 使用的槽位布局.
     *
     * @return Pane 布局
     */
    @NotNull
    Structure structure();

    /**
     * 返回 Pane 宽度.
     *
     * @return Pane 宽度
     */
    default int width() {
        return this.size().width();
    }

    /**
     * 返回 Pane 高度.
     *
     * @return Pane 高度
     */
    default int height() {
        return this.size().height();
    }

    /**
     * 返回 Pane 槽位总数.
     *
     * @return Pane 槽位总数
     */
    default int area() {
        return this.size().area();
    }

    /**
     * 返回指定槽位当前保存的元素.
     *
     * @param slot 槽位编号
     * @return 槽位元素
     */
    @NotNull
    Element element(int slot);

    @NotNull
    default Element element(int x, int y) {
        return this.element(this.size().indexOf(x, y));
    }

    /**
     * 复制 Pane 中的所有槽位元素.
     *
     * @return 元素数组副本
     */
    Element @NotNull [] elements();

    /**
     * 返回槽位是否不为空.
     *
     * @param slot 槽位编号
     * @return 槽位有内容时为 true
     */
    default boolean hasElement(int slot) {
        return this.element(slot) != Element.Empty.INSTANCE;
    }

    /**
     * 返回坐标对应槽位是否不为空.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @return 槽位有内容时为 true
     */
    default boolean hasElement(int x, int y) {
        return this.hasElement(this.size().indexOf(x, y));
    }

    /**
     * 返回槽位中的 Item, 该槽位不是 Item 时返回 null.
     *
     * @param slot 槽位编号
     * @return Item, 或 null
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
     * 返回槽位的 Structure 标志符.
     *
     * @param slot 槽位编号
     * @return 标志符, 或 null
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
     * 返回槽位是否使用指定 Structure 标志符.
     *
     * @param slot 槽位编号
     * @param identifier 标志符
     * @return 标志符相同时为 true
     */
    default boolean isTagged(int slot, @NotNull String identifier) {
        return identifier.equals(this.identifierAt(slot));
    }

    default boolean isTagged(int x, int y, @NotNull String identifier) {
        return this.isTagged(this.size().indexOf(x, y), identifier);
    }

    /**
     * 选择所有使用指定 Structure 标志符的槽位.
     *
     * @param identifier 标志符
     * @return 对应槽位选择
     */
    @NotNull
    default SlotSequence slots(@NotNull String identifier) {
        return this.structure().slots(identifier);
    }

    /**
     * 选择一个或多个标志符的槽位, 再使用 Pattern 决定取舍和顺序.
     *
     * @param pattern 槽位选择方式
     * @param identifiers 要合并的标志符
     * @return 筛选并排列后的槽位选择
     */
    @NotNull
    default SlotSequence slots(@NotNull SlotPattern pattern, String @NotNull ... identifiers) {
        return this.structure().slots(pattern, identifiers);
    }

    /**
     * 替换指定槽位的元素.
     *
     * @param slot 槽位编号
     * @param element 新元素
     */
    void setElement(int slot, @NotNull Element element);

    default void setElement(int x, int y, @NotNull Element element) {
        this.setElement(this.size().indexOf(x, y), element);
    }

    /**
     * 把同一标志符的所有槽位替换为同一元素.
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

    default void setElement(@NotNull String identifier, @NotNull ElementSupplier supplier) {
        this.setElements(this.slots(identifier), supplier, true);
    }

    /**
     * 为选中槽位生成元素, 全部生成成功后再一次写入 Pane.
     * <p>{@code replaceExisting} 为 false 时只填充空槽位. Supplier 失败时 Pane 保持不变.</p>
     *
     * @param slots 要写入的槽位选择
     * @param supplier 元素生成器
     * @param replaceExisting 是否覆盖已有内容
     */
    void setElements(@NotNull SlotSequence slots, @NotNull ElementSupplier supplier, boolean replaceExisting);

    /**
     * 设置指定槽位显示的 Item.
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
     * 设置同一标志符的所有槽位显示同一 Item.
     *
     * @param identifier 标志符
     * @param item Item
     */
    default void setItem(@NotNull String identifier, @NotNull Item item) {
        this.setElement(identifier, new Element.Item(item));
    }

    default void setItem(@NotNull String identifier, @NotNull Supplier<? extends Item> supplier) {
        this.setElements(this.slots(identifier), ElementSupplier.items(supplier), true);
    }

    /**
     * 把一个槽位连接到子 Pane 槽位.
     *
     * @param slot 当前 Pane 槽位
     * @param pane 子 Pane
     * @param paneSlot 子 Pane 槽位
     */
    default void setPane(int slot, @NotNull Pane pane, int paneSlot) {
        this.setElement(slot, new Element.PaneLink(pane, paneSlot));
    }

    /**
     * 按二维形状把同一标志符的槽位连接到子 Pane.
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
     * 按二维形状把标志槽位连接到子 Pane 的指定偏移位置.
     *
     * @param identifier 标志符
     * @param pane 子 Pane
     * @param offsetX 子 Pane 横向偏移
     * @param offsetY 子 Pane 纵向偏移
     */
    default void setPane(@NotNull String identifier, @NotNull Pane pane, int offsetX, int offsetY) {
        this.setElements(
                this.slots(identifier),
                ElementSupplier.pane(pane, offsetX, offsetY),
                true
        );
    }

    /**
     * 按参数顺序把元素放入 Pane 中最靠前的空槽位.
     *
     * @param elements 要添加的元素
     */
    void addElements(Element @NotNull ... elements);

    /**
     * 按参数顺序把 Item 放入 Pane 中最靠前的空槽位.
     *
     * @param items 要添加的 Item
     */
    void addItems(Item @NotNull ... items);

    /**
     * 标记选中槽位需要重新显示, 即使槽位中仍是同一个元素.
     *
     * @param slots 需要刷新的槽位选择
     */
    void dirty(@NotNull SlotSequence slots);

    /**
     * 标记一个槽位需要重新显示.
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
     * 用同一元素填充所有槽位.
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
     * 用同一 Item 填充所有槽位.
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
     * 用同一 Item 填充指定槽位范围.
     *
     * @param startInclusive 起始槽位, 包含
     * @param endExclusive 结束槽位, 不包含
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
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
     * 用同一 Item 填充一整行.
     *
     * @param row 行号
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
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
     * 用同一 Item 填充一整列.
     *
     * @param column 列号
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
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
     * 用同一 Item 填充 Pane 边框.
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
     * 用同一 Item 填充一个矩形范围.
     *
     * @param x 矩形左上角 x 坐标
     * @param y 矩形左上角 y 坐标
     * @param width 矩形宽度
     * @param height 矩形高度
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
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
     * 按子 Pane 尺寸把一个矩形范围连接到子 Pane.
     *
     * @param x 矩形左上角 x 坐标
     * @param y 矩形左上角 y 坐标
     * @param child 子 Pane
     * @param replaceExisting 是否覆盖已有内容
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
     * 返回本 Pane 的视觉配置: 两层视觉映射与空槽背景.
     * <p>同一 Pane 始终返回同一个对象; 配置对显示这个 Pane 的所有 Window 生效.
     *
     * @return 视觉配置
     */
    @NotNull
    PaneVisual visual();

    /**
     * 返回当前的全局视觉映射.
     *
     * @return 全局视觉映射; 没有设置过时为 {@code null}, 表示按路径终点显示
     */
    @Nullable
    default Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.visual().visualizerProvider();
    }

    /**
     * 设置 Pane 全局视觉映射. 映射盖在经过本 Pane 的显示路径上, 命中时路径终点不再参与显示;
     * 输入是路径终点的同步可读内容, 约定见 {@link PaneVisual}. 返回 {@code null} 表示放行, 交给下一层.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示不参与这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.visual().setVisualizerProvider(visualizerProvider);
    }

    /**
     * 设置 Pane 全局视觉映射, 并指定 Provider 给出结果前显示的占位.
     * <p>约定与 {@link #setVisualizerProvider(Function)} 相同, Provider 当场算得出结果时首帧就是真值, 用不到占位.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示不参与这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示终点连接 Inventory 时显示该槽真实内容, 其余终点显示空
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        this.visual().setVisualizerProvider(visualizerProvider, placeholder);
    }

    /**
     * 使用直接返回 ItemStack 的映射设置 Pane 全局视觉映射.
     * <p>约定与 {@link #setVisualizerProvider(Function)} 相同.
     *
     * @param visualizer 新的全局物品映射, {@code null} 表示不参与这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visual().setVisualizerItem(visualizer);
    }

    /**
     * 返回一个 Pane 槽位的显式视觉映射; 不含回退到的全局映射.
     *
     * @param slot Pane 槽位
     * @return 该槽的逐槽视觉映射; 没有覆盖时为 {@code null}, 表示这个槽用的是全局映射
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    default Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider(int slot) {
        return this.visual().visualizerProvider(slot);
    }

    /**
     * 替换一个 Pane 槽位的逐槽视觉映射, 它是本 Pane 层级最高的一层:
     * 返回非 {@code null} 结果直接采用, 返回 {@code null} 表示放行, 继续询问全局映射.
     * 传入 {@code null} 会移除这一层, 使该槽直接从全局映射开始.
     * <p>映射的输入输出约定与 {@link #setVisualizerProvider(Function)} 相同.
     *
     * @param slot Pane 槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    default void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.visual().setVisualizerProvider(slot, visualizerProvider);
    }

    /**
     * 替换一个 Pane 槽位的逐槽视觉映射, 并指定 Provider 给出结果前显示的占位.
     * <p>约定与 {@link #setVisualizerProvider(int, Function)} 相同.
     *
     * @param slot Pane 槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示终点连接 Inventory 时显示该槽真实内容, 其余终点显示空
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    default void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        this.visual().setVisualizerProvider(slot, visualizerProvider, placeholder);
    }

    /**
     * 使用直接返回 ItemStack 的映射替换一个 Pane 槽位的逐槽视觉映射.
     * 映射返回 {@code null} 表示放行, 返回空 ItemStack 表示覆盖为空视觉.
     *
     * @param slot Pane 槽位
     * @param visualizer 新的逐槽物品映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    default void setVisualizerItem(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visual().setVisualizerItem(slot, visualizer);
    }


    /**
     * 返回空槽位使用的背景, 没有背景时返回 null.
     *
     * @return Pane 背景, 或 null
     */
    @Nullable
    ItemProvider background();

    /**
     * 更改空槽位使用的背景.
     *
     * @param background Pane 背景, null 表示清除背景
     */
    void setBackground(@Nullable ItemProvider background);

    /**
     * 使用 ItemStack 更改空槽位使用的背景.
     *
     * @param background Pane 背景
     */
    default void setBackgroundItem(@NotNull ItemStack background) {
        this.setBackground(ItemProvider.constant(background));
    }

    /**
     * 返回 Pane 是否已禁止玩家交互.
     *
     * @return 禁止交互时为 true
     */
    boolean frozen();

    /**
     * 设置是否禁止玩家与 Pane 中的 Item 交互.
     *
     * @param frozen true 表示禁止交互
     */
    void setFrozen(boolean frozen);

    /**
     * 声明一个额外参与本 Pane 所在 Window 的 Inventory.
     * <p>它的槽位一个都不必被展示: 快速转移与双击收集寻找目标时照样会看到它.
     * 用来表达"这块面板背后还连着别的容器", 例如分页仓库当前只展示第一页, 却希望 Shift 点击能落进其余几页.
     * <p><strong>必须同时打开该 Inventory 的 {@link SparrowInventory#includeObscuredSlots(boolean)}.</strong>
     * <p><strong>声明只在本 Pane 处于当前显示路径上时生效.</strong>
     *
     * @param inventory 要额外带进参与集的 Inventory
     */
    void linkInventory(@NotNull SparrowInventory inventory);

    /**
     * 取消一个逐个声明进来的额外参与 Inventory.
     * <p>只认逐个声明的那些. 经 {@link #linkInventory(InventorySequence)} 整条带进来的要摘掉整条序列.
     *
     * @param inventory 要取消的 Inventory
     * @return 该 Inventory 原本已被逐个声明时返回 true
     */
    boolean unlinkInventory(@NotNull SparrowInventory inventory);

    /**
     * 返回逐个声明进来的那些额外参与 Inventory.
     * <p>不含经 {@link #linkInventory(InventorySequence)} 整条带进来的成员.
     *
     * @return 按声明顺序排列的不可变快照
     */
    @NotNull
    List<SparrowInventory> linkedInventories();

    /**
     * 声明一整个序列额外参与本 Pane 所在 Window, 语义同
     * {@link #linkInventory(SparrowInventory)}, 只是成员随序列变化, 不必逐个声明.
     * <p>参与集在每次规划时现取序列的成员, 因此中途增减成员立刻生效, 不需要重新声明.
     *
     * @param sequence 要额外带进参与集的序列
     */
    void linkInventory(@NotNull InventorySequence sequence);

    /**
     * 取消一个已声明的额外参与序列.
     *
     * @param sequence 要取消的序列
     * @return 该序列原本已被声明时返回 true
     */
    boolean unlinkInventory(@NotNull InventorySequence sequence);

    /**
     * 返回整条声明进来的那些额外参与序列.
     * <p>不含逐个声明汇成的那条内部序列, 逐个声明的看 {@link #linkedInventories()}.
     *
     * @return 按声明顺序排列的不可变快照
     */
    @NotNull
    Set<InventorySequence> linkedSequences();

    /**
     * 返回本 Pane 带进参与集的全部序列.
     *
     * @return 按声明顺序排列的不可变快照
     */
    @NotNull
    @ApiStatus.Internal
    Set<InventorySequence> participatingSequences();

    /**
     * 订阅一个槽位的更新, 并返回订阅时的元素和冻结状态.
     *
     * @param slot 槽位编号
     * @param observer 槽位更新观察者
     * @return 订阅和当前状态
     */
    @NotNull
    PaneSlotAttachment attach(int slot, @NotNull Observer<? super Pane> observer);

    /**
     * 绑定到指定的 Signal, Signal 将会持有本类的弱引用.
     * <p>绑定不补发当前值, 第一次回调发生在下一次标脏.
     * <p>绑定由本对象持有, 本对象被回收时一并消失, {@code callback} 捕获的对象随本对象一起释放.
     *
     * @param signal 数据源
     * @param callback 失效回调
     * @return 订阅凭证, 可用于提前解绑.
     */
    @NotNull
    Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super Pane> callback);

    /**
     * 让选中槽位的内容一直跟随一个序列展示,序列的第 n 项写进选中槽位的第 n 个.
     * <p>创建时就地求值一次, 之后每次序列失效都在 Paper 全局异步调度器上重算,
     * 因此序列的派生函数与 {@code toElement} 都只能读那些在异步域访问安全的数据.
     *
     * @param slots 投影负责的槽位, 必须属于本 Pane
     * @param source 序列来源
     * @param toElement 把序列里的一条数据变成一个 Element, 不得返回 {@code null}
     * @return 投影, 可用来提前停止
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
     * 让选中槽位的内容一直跟随一个序列, 并指定在哪里求值.
     * <p>要在 {@code toElement} 里读某个玩家的实体域数据时, 换成该玩家的实体调度.
     *
     * @param slots 投影负责的槽位, 必须属于本 Pane
     * @param source 序列来源
     * @param toElement 把序列里的一条数据变成一个 Element, 不得返回 {@code null}
     * @param executor 执行求值的执行器
     * @return 投影, 可用来提前停止
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
     * 让选中槽位的内容一直跟随一个已经是 Element 的序列.
     *
     * @param slots 投影负责的槽位, 必须属于本 Pane
     * @param source 序列来源
     * @return 投影, 可用来提前停止
     */
    @NotNull
    default SlotProjection projectElements(
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends Element>> source
    ) {
        return SlotProjection.attachElements(this, slots, source);
    }

    /**
     * 让选中槽位的内容一直跟随一个已经是 Element 的序列, 并指定在哪里求值.
     *
     * @param slots 投影负责的槽位, 必须属于本 Pane
     * @param source 序列来源
     * @param executor 执行求值的执行器
     * @return 投影, 可用来提前停止
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
     * 通过 Structure 标志符填充槽位, 并创建 Pane.
     */
    interface Builder<G extends Pane, B extends Builder<G, B>> {

        /**
         * 返回 Builder 正在使用的布局.
         *
         * @return Pane 布局
         */
        @NotNull
        Structure structure();

        /**
         * 为指定标志符的每个槽位绑定元素生成器.
         *
         * @param identifier 标志符
         * @param supplier 元素生成器
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull ElementSupplier supplier);

        /**
         * 为单字符标志的每个槽位绑定元素生成器.
         *
         * @param identifier 单字符标志
         * @param supplier 元素生成器
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull ElementSupplier supplier);

        /**
         * 把同一标志符的槽位绑定为同一元素.
         *
         * @param identifier 标志符
         * @param element 槽位元素
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Element element);

        /**
         * 把单字符标志的槽位绑定为同一元素.
         *
         * @param identifier 单字符标志
         * @param element 槽位元素
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Element element);

        /**
         * 把同一标志符的槽位绑定为同一 Item.
         *
         * @param identifier 标志符
         * @param item Item
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Item item);

        /**
         * 把单字符标志的槽位绑定为同一 Item.
         *
         * @param identifier 单字符标志
         * @param item Item
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Item item);

        /**
         * 为同一标志符的每个槽位调用 ItemBuilder.
         *
         * @param identifier 标志符
         * @param itemBuilder Item Builder
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull ItemBuilder itemBuilder);

        /**
         * 把同一标志符的槽位绑定为同一 ItemProvider.
         *
         * @param identifier 标志符
         * @param provider Item 内容来源
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull ItemProvider provider);

        /**
         * 把同一标志符的槽位绑定为指定 ItemStack.
         *
         * @param identifier 标志符
         * @param itemStack Bukkit ItemStack
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull ItemStack itemStack);

        /**
         * 为同一标志符的每个槽位创建 Item.
         *
         * @param identifier 标志符
         * @param itemSupplier Item 来源
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Supplier<? extends Item> itemSupplier);

        /**
         * 为指定标志符的每个槽位单独调用 Supplier.
         *
         * @param identifier 标志符
         * @param elementSupplier 槽位元素来源
         * @return 当前 Builder
         */
        @NotNull
        B addIngredientElementSupplier(@NotNull String identifier, @NotNull Supplier<? extends Element> elementSupplier);

        /**
         * 把同一标志符的槽位按出现顺序循环连接到 Inventory.
         * 标志符第 n 次出现(从 0 开始)的槽位展示并操作 Inventory 的 {@code n % inventory.size()} 槽位;
         * 零尺寸 Inventory 生成空槽位.
         *
         * @param identifier 标志符
         * @param inventory 连接的 Inventory
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull SparrowInventory inventory);

        /**
         * 把单字符标志的槽位按出现顺序循环连接到 Inventory.
         * 出现次数超过 Inventory 尺寸时从槽位 0 重新开始, 零尺寸 Inventory 生成空槽位.
         *
         * @param identifier 单字符标志
         * @param inventory 连接的 Inventory
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull SparrowInventory inventory);

        /**
         * 让同一标志符的槽位一直跟随一个序列, 序列的第 n 项写进该标志符第 n 次出现的槽位.
         * <p>序列本身已经是 Element 时, 用 {@code addModifier(pane -> pane.projectElements(pane.slots(identifier), ...))}.
         *
         * @param identifier 标志符
         * @param source 序列来源
         * @param toElement 把序列里的一条数据变成一个 Element, 不得返回 {@code null}
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                @NotNull String identifier,
                @NotNull Signal<? extends List<? extends T>> source,
                @NotNull Function<? super T, ? extends Element> toElement
        );

        /**
         * 让单字符标志的槽位一直跟随一个序列.
         *
         * @param identifier 单字符标志
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
         * 让同一标志符的槽位一直跟随一个序列, 并指定在哪里求值.
         *
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
         * 让单字符标志的槽位一直跟随一个序列, 并指定在哪里求值.
         *
         * @param identifier 单字符标志
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
         * 让同一标志符的槽位一直跟随一个翻页的当前页, 当前页第 n 条写进该标志符第 n 次出现的槽位.
         *
         * @param identifier 标志符
         * @param page 翻页
         * @param toElement 把当前页里的一条数据变成一个 Element, 不得返回 {@code null}
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                @NotNull String identifier,
                @NotNull Page<T> page,
                @NotNull Function<? super T, ? extends Element> toElement
        );

        /**
         * 让单字符标志的槽位一直跟随一个翻页的当前页.
         *
         * @param identifier 单字符标志
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
         * 让同一标志符的槽位一直跟随一个翻页的当前页, 并指定在哪里求值.
         *
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
         * 让单字符标志的槽位一直跟随一个翻页的当前页, 并指定在哪里求值.
         *
         * @param identifier 单字符标志
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
         * 让同一标志符的槽位一直跟随一个翻页的当前页, 页里的内容已经是 Item.
         *
         * @param identifier 标志符
         * @param page 内容是 Item 的翻页
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Page<? extends Item> page);

        /**
         * 让单字符标志的槽位一直跟随一个翻页的当前页, 页里的内容已经是 Item.
         *
         * @param identifier 单字符标志
         * @param page 内容是 Item 的翻页
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Page<? extends Item> page);

        /**
         * 让同一标志符的槽位一直跟随一个滚动的当前屏, 这一屏第 n 条写进该标志符第 n 次出现的槽位.
         * <p>槽位顺序跟着滚动方向走, 竖着滚按行, 横着滚按列.
         *
         * @param identifier 标志符
         * @param scroll 滚动
         * @param toElement 把这一屏里的一条数据变成一个 Element, 不得返回 {@code null}
         * @return 当前 Builder
         */
        @NotNull
        <T> B addIngredient(
                @NotNull String identifier,
                @NotNull Scroll<T> scroll,
                @NotNull Function<? super T, ? extends Element> toElement
        );

        /**
         * 让单字符标志的槽位一直跟随一个滚动的当前屏.
         *
         * @param identifier 单字符标志
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
         * 让同一标志符的槽位一直跟随一个滚动的当前屏, 并指定在某个执行器求值.
         *
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
         * 让单字符标志的槽位一直跟随一个滚动的当前屏, 并指定在哪里求值.
         *
         * @param identifier 单字符标志
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
         * 让同一标志符的槽位一直跟随一个滚动的当前屏, 屏里的内容已经是 Item.
         *
         * @param identifier 标志符
         * @param scroll 内容是 Item 的滚动
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Scroll<? extends Item> scroll);

        /**
         * 让单字符标志的槽位一直跟随一个滚动的当前屏, 屏里的内容已经是 Item.
         *
         * @param identifier 单字符标志
         * @param scroll 内容是 Item 的滚动
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Scroll<? extends Item> scroll);

        /**
         * 让同一标志符的槽位一直显示标签组当前选中的子 Pane: 区域保持二维形状连接过去, 切换标签时整片重铺.
         * <p>子 Pane 盖不住的槽位显示为空; 切到已经选中的标签什么都不会发生.
         *
         * @param identifier 标志符
         * @param tab 标签组
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Tab<?> tab);

        /**
         * 让单字符标志的槽位一直显示标签组当前选中的子 Pane.
         *
         * @param identifier 单字符标志
         * @param tab 标签组
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull Tab<?> tab);

        /**
         * 按二维形状把同一标志符的槽位连接到子 Pane.
         *
         * @param identifier 标志符
         * @param pane 子 Pane
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Pane pane);

        /**
         * 按二维形状把标志槽位连接到子 Pane 的指定偏移位置.
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
         * 设置空槽位使用的背景.
         *
         * @param background Pane 背景, null 表示清除背景
         * @return 当前 Builder
         */
        @NotNull
        B setBackground(@Nullable ItemProvider background);

        /**
         * 使用 ItemStack 设置空槽位背景.
         *
         * @param background 背景 ItemStack
         * @return 当前 Builder
         */
        @NotNull
        B setBackground(@NotNull ItemStack background);

        /**
         * 设置是否禁止玩家与 Pane 中的 Item 交互.
         *
         * @param frozen true 表示禁止交互
         * @return 当前 Builder
         */
        @NotNull
        B setFrozen(boolean frozen);

        /**
         * 声明一个额外参与的 Inventory, 语义见 {@link Pane#linkInventory(SparrowInventory)}.
         *
         * @param inventory 要额外带进参与集的 Inventory
         * @return 当前 Builder
         */
        @NotNull
        B linkInventory(@NotNull SparrowInventory inventory);

        /**
         * 添加一个在 Pane 创建后执行的修改操作.
         *
         * @param modifier Pane 修改操作
         * @return 当前 Builder
         */
        @NotNull
        B addModifier(@NotNull Consumer<? super G> modifier);

        /**
         * 替换全部构建后修改操作.
         *
         * @param modifiers Pane 修改操作
         * @return 当前 Builder
         */
        @NotNull
        B setModifiers(@NotNull List<? extends Consumer<? super G>> modifiers);

        /**
         * 创建可独立修改的 Builder 副本.
         *
         * @return Builder 副本
         */
        @NotNull
        B copy();

        /**
         * 根据当前配置创建一个新 Pane.
         *
         * @return 新 Pane
         */
        @NotNull
        G build();
    }
}
