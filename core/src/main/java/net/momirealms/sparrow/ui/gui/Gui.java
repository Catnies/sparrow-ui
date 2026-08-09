package net.momirealms.sparrow.ui.gui;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemBuilder;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public sealed interface Gui permits AbstractGui {

    /**
     * 为指定尺寸创建普通 GUI Builder.
     *
     * @param size GUI 尺寸
     * @return 普通 GUI Builder
     */
    @NotNull
    static Builder<NormalGui, ?> builder(@NotNull GuiSize size) {
        return NormalGui.builder(size);
    }

    /**
     * 为指定宽高创建普通 GUI Builder.
     *
     * @param width GUI 宽度
     * @param height GUI 高度
     * @return 普通 GUI Builder
     */
    @NotNull
    static Builder<NormalGui, ?> builder(int width, int height) {
        return builder(new GuiSize(width, height));
    }

    /**
     * 使用已有布局创建普通 GUI Builder.
     *
     * @param structure GUI 布局
     * @return 普通 GUI Builder
     */
    @NotNull
    static Builder<NormalGui, ?> builder(@NotNull Structure structure) {
        return NormalGui.builder(structure);
    }

    /**
     * 先解析多行布局, 再创建普通 GUI Builder.
     *
     * @param rows 布局模板行
     * @return 普通 GUI Builder
     */
    @NotNull
    static Builder<NormalGui, ?> builder(String @NotNull ... rows) {
        return NormalGui.builder(Structure.of(rows));
    }

    /**
     * 先解析连续布局文本, 再创建普通 GUI Builder.
     *
     * @param width GUI 宽度
     * @param height GUI 高度
     * @param flatData 连续布局文本
     * @return 普通 GUI Builder
     */
    @NotNull
    static Builder<NormalGui, ?> builder(int width, int height, @NotNull String flatData) {
        return builder(Structure.of(new GuiSize(width, height), flatData));
    }

    /**
     * 创建所有槽位都为空的普通 GUI.
     *
     * @param width GUI 宽度
     * @param height GUI 高度
     * @return 空 GUI
     */
    @NotNull
    static NormalGui empty(int width, int height) {
        return NormalGui.empty(new GuiSize(width, height));
    }

    /**
     * 创建所有槽位都为空的普通 GUI.
     *
     * @param size GUI 尺寸
     * @return 空 GUI
     */
    @NotNull
    static NormalGui empty(@NotNull GuiSize size) {
        return NormalGui.empty(size);
    }

    /**
     * 创建每个槽位都显示同一 Item 的普通 GUI.
     *
     * @param width GUI 宽度
     * @param height GUI 高度
     * @param item 每个槽位显示的 Item
     * @return 填满 Item 的 GUI
     */
    @NotNull
    static NormalGui filled(int width, int height, @NotNull Item item) {
        NormalGui gui = empty(width, height);
        gui.fill(item);
        return gui;
    }

    /**
     * 使用已有布局创建所有槽位都为空的普通 GUI.
     *
     * @param structure GUI 布局
     * @return 空 GUI
     */
    @NotNull
    static NormalGui of(@NotNull Structure structure) {
        return NormalGui.from(structure);
    }

    /**
     * 创建只有一个槽位的 GUI.
     *
     * @param item 唯一槽位显示的 Item
     * @return 单槽位 GUI
     */
    @NotNull
    static NormalGui single(@NotNull Item item) {
        return NormalGui.builder(new GuiSize(1, 1))
                .addModifier(gui -> gui.setItem(0, item))
                .build();
    }

    /**
     * 返回 GUI 的宽高.
     *
     * @return GUI 尺寸
     */
    @NotNull
    GuiSize size();

    /**
     * 返回 GUI 使用的槽位布局.
     *
     * @return GUI 布局
     */
    @NotNull
    Structure structure();

    /**
     * 返回 GUI 宽度.
     *
     * @return GUI 宽度
     */
    default int width() {
        return this.size().width();
    }

    /**
     * 返回 GUI 高度.
     *
     * @return GUI 高度
     */
    default int height() {
        return this.size().height();
    }

    /**
     * 返回 GUI 槽位总数.
     *
     * @return GUI 槽位总数
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
    SlotElement element(int slot);

    /**
     * 按坐标返回槽位元素.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @return 槽位元素
     */
    @NotNull
    default SlotElement element(int x, int y) {
        return this.element(this.size().indexOf(x, y));
    }

    /**
     * 复制 GUI 中的所有槽位元素.
     *
     * @return 元素数组副本
     */
    SlotElement @NotNull [] elements();

    /**
     * 返回槽位是否不为空.
     *
     * @param slot 槽位编号
     * @return 槽位有内容时为 true
     */
    default boolean hasElement(int slot) {
        return this.element(slot) != SlotElement.Empty.INSTANCE;
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
        return this.element(slot) instanceof SlotElement.Item(var item) ? item : null;
    }

    /**
     * 按坐标返回槽位中的 Item, 该槽位不是 Item 时返回 null.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @return Item, 或 null
     */
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

    /**
     * 按坐标返回槽位的 Structure 标志符.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @return 标志符, 或 null
     */
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

    /**
     * 返回坐标对应槽位是否使用指定 Structure 标志符.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @param identifier 标志符
     * @return 标志符相同时为 true
     */
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
    void setElement(int slot, @NotNull SlotElement element);

    /**
     * 按坐标替换槽位元素.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @param element 新元素
     */
    default void setElement(int x, int y, @NotNull SlotElement element) {
        this.setElement(this.size().indexOf(x, y), element);
    }

    /**
     * 把同一标志符的所有槽位替换为同一元素.
     *
     * @param identifier 标志符
     * @param element 新元素
     */
    default void setElement(@NotNull String identifier, @NotNull SlotElement element) {
        this.setElements(this.slots(identifier), SlotElementSupplier.fixed(element), true);
    }

    /**
     * 把同一单字符标志的所有槽位替换为同一元素.
     *
     * @param identifier 单字符标志
     * @param element 新元素
     */
    default void setElement(char identifier, @NotNull SlotElement element) {
        this.setElement(String.valueOf(identifier), element);
    }

    /**
     * 为同一标志符的每个槽位生成新元素.
     *
     * @param identifier 标志符
     * @param supplier 元素生成器
     */
    default void setElement(@NotNull String identifier, @NotNull SlotElementSupplier supplier) {
        this.setElements(this.slots(identifier), supplier, true);
    }

    /**
     * 为选中槽位生成元素, 全部生成成功后再一次写入 GUI.
     *
     * <p>{@code replaceExisting} 为 false 时只填充空槽位. Supplier 失败时 GUI 保持不变.</p>
     *
     * @param slots 要写入的槽位选择
     * @param supplier 元素生成器
     * @param replaceExisting 是否覆盖已有内容
     */
    void setElements(@NotNull SlotSequence slots, @NotNull SlotElementSupplier supplier, boolean replaceExisting);

    /**
     * 设置指定槽位显示的 Item.
     *
     * @param slot 槽位编号
     * @param item Item
     */
    default void setItem(int slot, @NotNull Item item) {
        this.setElement(slot, new SlotElement.Item(item));
    }

    /**
     * 按坐标设置槽位显示的 Item.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @param item Item
     */
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
        this.setElement(identifier, new SlotElement.Item(item));
    }

    /**
     * 为同一标志符的每个槽位创建 Item.
     *
     * @param identifier 标志符
     * @param supplier Item 来源
     */
    default void setItem(@NotNull String identifier, @NotNull Supplier<? extends Item> supplier) {
        this.setElements(this.slots(identifier), SlotElementSupplier.items(supplier), true);
    }

    /**
     * 把一个槽位连接到子 GUI 槽位.
     *
     * @param slot 当前 GUI 槽位
     * @param gui 子 GUI
     * @param guiSlot 子 GUI 槽位
     */
    default void setGui(int slot, @NotNull Gui gui, int guiSlot) {
        this.setElement(slot, new SlotElement.GuiLink(gui, guiSlot));
    }

    /**
     * 按二维形状把同一标志符的槽位连接到子 GUI.
     *
     * @param identifier 标志符
     * @param gui 子 GUI
     */
    default void setGui(@NotNull String identifier, @NotNull Gui gui) {
        this.setGui(identifier, gui, 0, 0);
    }

    /**
     * 按二维形状把同一单字符标志的槽位连接到子 GUI.
     *
     * @param identifier 单字符标志
     * @param gui 子 GUI
     */
    default void setGui(char identifier, @NotNull Gui gui) {
        this.setGui(String.valueOf(identifier), gui);
    }

    /**
     * 按二维形状把标志槽位连接到子 GUI 的指定偏移位置.
     *
     * @param identifier 标志符
     * @param gui 子 GUI
     * @param offsetX 子 GUI 横向偏移
     * @param offsetY 子 GUI 纵向偏移
     */
    default void setGui(@NotNull String identifier, @NotNull Gui gui, int offsetX, int offsetY) {
        this.setElements(
                this.slots(identifier),
                SlotElementSupplier.gui(gui, offsetX, offsetY),
                true
        );
    }

    /**
     * 按参数顺序把元素放入 GUI 中最靠前的空槽位.
     *
     * @param elements 要添加的元素
     */
    void addElements(SlotElement @NotNull ... elements);

    /**
     * 按参数顺序把 Item 放入 GUI 中最靠前的空槽位.
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

    /**
     * 按坐标标记槽位需要重新显示.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     */
    default void dirty(int x, int y) {
        this.dirty(this.size().indexOf(x, y));
    }

    /**
     * 标记同一标志符的所有槽位需要重新显示.
     *
     * @param identifier 标志符
     */
    default void dirty(@NotNull String identifier) {
        this.dirty(this.slots(identifier));
    }

    /**
     * 用同一元素覆盖所有槽位.
     *
     * @param element 槽位元素
     */
    default void fillElement(@NotNull SlotElement element) {
        this.fillElement(element, true);
    }

    /**
     * 用同一元素填充所有槽位.
     *
     * @param element 槽位元素
     * @param replaceExisting 是否覆盖已有内容
     */
    default void fillElement(@NotNull SlotElement element, boolean replaceExisting) {
        this.setElements(
                SlotSequence.all(this.size()),
                SlotElementSupplier.fixed(element),
                replaceExisting
        );
    }

    /**
     * 用同一 Item 覆盖所有槽位.
     *
     * @param item Item
     */
    default void fill(@NotNull Item item) {
        this.fill(item, true);
    }

    /**
     * 用同一 Item 填充所有槽位.
     *
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
     */
    default void fill(@NotNull Item item, boolean replaceExisting) {
        this.fillElement(new SlotElement.Item(item), replaceExisting);
    }

    /**
     * 用同一 Item 覆盖指定槽位范围.
     *
     * @param startInclusive 起始槽位, 包含
     * @param endExclusive 结束槽位, 不包含
     * @param item Item
     */
    default void fill(int startInclusive, int endExclusive, @NotNull Item item) {
        this.fill(startInclusive, endExclusive, item, true);
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
                SlotElementSupplier.fixed(new SlotElement.Item(item)),
                replaceExisting
        );
    }

    /**
     * 用同一 Item 覆盖一整行.
     *
     * @param row 行号
     * @param item Item
     */
    default void fillRow(int row, @NotNull Item item) {
        this.fillRow(row, item, true);
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
                SlotElementSupplier.fixed(new SlotElement.Item(item)),
                replaceExisting
        );
    }

    /**
     * 用同一 Item 覆盖一整列.
     *
     * @param column 列号
     * @param item Item
     */
    default void fillColumn(int column, @NotNull Item item) {
        this.fillColumn(column, item, true);
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
                SlotElementSupplier.fixed(new SlotElement.Item(item)),
                replaceExisting
        );
    }

    /**
     * 用同一 Item 覆盖 GUI 边框.
     *
     * @param item Item
     */
    default void fillBorders(@NotNull Item item) {
        this.fillBorders(item, true);
    }

    /**
     * 用同一 Item 填充 GUI 边框.
     *
     * @param item Item
     * @param replaceExisting 是否覆盖已有内容
     */
    default void fillBorders(@NotNull Item item, boolean replaceExisting) {
        this.setElements(
                SlotSequence.borders(this.size()),
                SlotElementSupplier.fixed(new SlotElement.Item(item)),
                replaceExisting
        );
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
    default void fillRectangle(
            int x,
            int y,
            int width,
            int height,
            @NotNull Item item,
            boolean replaceExisting
    ) {
        this.setElements(
                SlotSequence.rectangle(this.size(), x, y, width, height),
                SlotElementSupplier.fixed(new SlotElement.Item(item)),
                replaceExisting
        );
    }

    /**
     * 用同一 Item 覆盖一个矩形范围.
     *
     * @param x 矩形左上角 x 坐标
     * @param y 矩形左上角 y 坐标
     * @param width 矩形宽度
     * @param height 矩形高度
     * @param item Item
     */
    default void fillRectangle(int x, int y, int width, int height, @NotNull Item item) {
        this.fillRectangle(x, y, width, height, item, true);
    }

    /**
     * 按子 GUI 尺寸把一个矩形范围连接到子 GUI.
     *
     * @param x 矩形左上角 x 坐标
     * @param y 矩形左上角 y 坐标
     * @param child 子 GUI
     * @param replaceExisting 是否覆盖已有内容
     */
    default void fillRectangle(int x, int y, @NotNull Gui child, boolean replaceExisting) {
        this.setElements(
                SlotSequence.rectangle(this.size(), x, y, child.width(), child.height()),
                SlotElementSupplier.gui(child),
                replaceExisting
        );
    }

    /**
     * 按子 GUI 尺寸把一个矩形范围连接到子 GUI.
     *
     * @param x 矩形左上角 x 坐标
     * @param y 矩形左上角 y 坐标
     * @param child 子 GUI
     */
    default void fillRectangle(int x, int y, @NotNull Gui child) {
        this.fillRectangle(x, y, child, true);
    }

    /**
     * 返回空槽位使用的背景, 没有背景时返回 null.
     *
     * @return GUI 背景, 或 null
     */
    @Nullable
    ItemProvider background();

    /**
     * 更改空槽位使用的背景.
     *
     * @param background GUI 背景, null 表示清除背景
     */
    void setBackground(@Nullable ItemProvider background);

    /**
     * 返回 GUI 是否已禁止玩家交互.
     *
     * @return 禁止交互时为 true
     */
    boolean frozen();

    /**
     * 设置是否禁止玩家与 GUI 中的 Item 交互.
     *
     * @param frozen true 表示禁止交互
     */
    void setFrozen(boolean frozen);

    /**
     * 订阅一个槽位的更新, 并返回订阅时的元素, 背景和冻结状态.
     *
     * @param slot 槽位编号
     * @param observer 槽位更新观察者
     * @return 订阅和当前状态
     */
    @NotNull
    GuiSlotAttachment attach(int slot, @NotNull Observer<? super Gui> observer);

    /**
     * 通过 Structure 标志符填充槽位, 并创建 GUI.
     *
     * <p>同一 Builder 可以重复构建 GUI. {@link #copy()} 返回可独立修改的副本.</p>
     *
     * @param <G> 构建结果类型
     * @param <B> 精确的 Builder 自类型
     */
    interface Builder<G extends Gui, B extends Builder<G, B>> {

        /**
         * 返回 Builder 正在使用的布局.
         *
         * @return GUI 布局
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
        B addIngredient(@NotNull String identifier, @NotNull SlotElementSupplier supplier);

        /**
         * 为单字符标志的每个槽位绑定元素生成器.
         *
         * @param identifier 单字符标志
         * @param supplier 元素生成器
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull SlotElementSupplier supplier);

        /**
         * 把同一标志符的槽位绑定为同一元素.
         *
         * @param identifier 标志符
         * @param element 槽位元素
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull SlotElement element);

        /**
         * 把单字符标志的槽位绑定为同一元素.
         *
         * @param identifier 单字符标志
         * @param element 槽位元素
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull SlotElement element);

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
        B addIngredientElementSupplier(
                @NotNull String identifier,
                @NotNull Supplier<? extends SlotElement> elementSupplier
        );

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
         * 出现次数超过 Inventory 尺寸时从槽位 0 重新开始; 零尺寸 Inventory 生成空槽位.
         *
         * @param identifier 单字符标志
         * @param inventory 连接的 Inventory
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(char identifier, @NotNull SparrowInventory inventory);

        /**
         * 按二维形状把同一标志符的槽位连接到子 GUI.
         *
         * @param identifier 标志符
         * @param gui 子 GUI
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Gui gui);

        /**
         * 按二维形状把标志槽位连接到子 GUI 的指定偏移位置.
         *
         * @param identifier 标志符
         * @param gui 子 GUI
         * @param offsetX 子 GUI 横向偏移
         * @param offsetY 子 GUI 纵向偏移
         * @return 当前 Builder
         */
        @NotNull
        B addIngredient(@NotNull String identifier, @NotNull Gui gui, int offsetX, int offsetY);

        /**
         * 设置空槽位使用的背景.
         *
         * @param background GUI 背景, null 表示清除背景
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
         * 设置是否禁止玩家与 GUI 中的 Item 交互.
         *
         * @param frozen true 表示禁止交互
         * @return 当前 Builder
         */
        @NotNull
        B setFrozen(boolean frozen);

        /**
         * 添加一个在 GUI 创建后执行的修改操作.
         *
         * @param modifier GUI 修改操作
         * @return 当前 Builder
         */
        @NotNull
        B addModifier(@NotNull Consumer<? super G> modifier);

        /**
         * 替换全部构建后修改操作.
         *
         * @param modifiers GUI 修改操作
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
         * 根据当前配置创建一个新 GUI.
         *
         * @return 新 GUI
         */
        @NotNull
        G build();
    }
}
