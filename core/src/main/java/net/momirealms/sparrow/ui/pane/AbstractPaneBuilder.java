package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemBuilder;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.page.Page;
import net.momirealms.sparrow.ui.pane.page.Scroll;
import net.momirealms.sparrow.ui.pane.page.Tab;
import net.momirealms.sparrow.ui.state.Signal;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pane Builder 的通用实现.
 * <p>它记录每个 Structure 标志符要填充的内容, 并统一处理背景,
 * 冻结状态, 构建后修改和失败位置诊断.
 */
abstract class AbstractPaneBuilder<G extends AbstractPane, B extends AbstractPaneBuilder<G, B>> implements Pane.Builder<G, B> {
    private final Structure structure; // Pane 布局
    private final ElementSupplier[] ingredients; // 按 Structure 内部标志符编号保存绑定
    private final ProjectionIngredient[] projections; // 与 ingredients 同下标, 建出 Pane 之后再挂上
    private final Tab<?>[] tabIngredients; // 与 ingredients 同下标, 建出 Pane 之后连接选中子 Pane
    private final ArrayList<Consumer<? super G>> modifiers; // Pane 创建后按顺序执行
    private final LinkedHashSet<SparrowInventory> linkedInventories; // 额外参与的 Inventory, 按声明顺序

    private ItemProvider background; // 空槽位背景, 可为 null
    private boolean frozen;          // 是否禁止玩家交互

    /**
     * 基于布局创建 Builder.
     *
     * @param structure Pane 布局
     */
    AbstractPaneBuilder(Structure structure) {
        this.structure = structure;
        this.ingredients = new ElementSupplier[structure.identifierCount()];
        this.projections = new ProjectionIngredient[structure.identifierCount()];
        this.tabIngredients = new Tab<?>[structure.identifierCount()];
        this.modifiers = new ArrayList<>();
        this.linkedInventories = new LinkedHashSet<>();
    }

    /**
     * 复制已有 Builder 的全部绑定与配置.
     *
     * @param source 来源 Builder
     */
    AbstractPaneBuilder(AbstractPaneBuilder<G, B> source) {
        this.structure = source.structure;
        this.ingredients = source.ingredients.clone();
        this.projections = source.projections.clone();
        this.tabIngredients = source.tabIngredients.clone();
        this.modifiers = new ArrayList<>(source.modifiers);
        this.linkedInventories = new LinkedHashSet<>(source.linkedInventories);
        this.background = source.background;
        this.frozen = source.frozen;
    }

    @Override
    @NotNull
    public final Structure structure() {
        return this.structure;
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull ElementSupplier supplier) {
        return this.bindIngredient(identifier, supplier);
    }

    @Override
    @NotNull
    public final B addIngredient(char identifier, @NotNull ElementSupplier supplier) {
        return this.addIngredient(String.valueOf(identifier), supplier);
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull Element element) {
        return this.bindIngredient(identifier, ElementSupplier.fixed(element));
    }

    @Override
    @NotNull
    public final B addIngredient(char identifier, @NotNull Element element) {
        return this.addIngredient(String.valueOf(identifier), element);
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull Item item) {
        return this.bindIngredient(identifier, ElementSupplier.fixed(new Element.Item(item)));
    }

    @Override
    @NotNull
    public final B addIngredient(char identifier, @NotNull Item item) {
        return this.addIngredient(String.valueOf(identifier), item);
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull ItemBuilder itemBuilder) {
        return this.bindIngredient(identifier, (ignoredSize, ignoredOccurrence) -> new Element.Item(itemBuilder.build()));
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull ItemProvider provider) {
        return this.addIngredient(identifier, Item.simple(provider));
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull ItemStack itemStack) {
        return this.addIngredient(identifier, Item.simple(ItemProvider.constant(itemStack)));
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull Supplier<? extends Item> itemSupplier) {
        return this.bindIngredient(identifier, ElementSupplier.items(itemSupplier));
    }

    @Override
    @NotNull
    public final B addIngredientElementSupplier(@NotNull String identifier, @NotNull Supplier<? extends Element> elementSupplier) {
        return this.bindIngredient(identifier, ElementSupplier.fromSupplier(elementSupplier));
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull SparrowInventory inventory) {
        return this.bindIngredient(identifier, ElementSupplier.inventory(inventory));
    }

    @Override
    @NotNull
    public final B addIngredient(char identifier, @NotNull SparrowInventory inventory) {
        return this.addIngredient(String.valueOf(identifier), inventory);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            @NotNull String identifier,
            @NotNull Signal<? extends List<? extends T>> source,
            @NotNull Function<? super T, ? extends Element> toElement
    ) {
        return this.addIngredient(identifier, source, toElement, SlotProjection.defaultExecutor());
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            char identifier,
            @NotNull Signal<? extends List<? extends T>> source,
            @NotNull Function<? super T, ? extends Element> toElement
    ) {
        return this.addIngredient(String.valueOf(identifier), source, toElement);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            @NotNull String identifier,
            @NotNull Signal<? extends List<? extends T>> source,
            @NotNull Function<? super T, ? extends Element> toElement,
            @NotNull Executor executor
    ) {
        @SuppressWarnings("unchecked")
        Function<Object, ? extends Element> erased = (Function<Object, ? extends Element>) toElement;
        return this.bindProjection(identifier, source, erased, executor, null);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            char identifier,
            @NotNull Signal<? extends List<? extends T>> source,
            @NotNull Function<? super T, ? extends Element> toElement,
            @NotNull Executor executor
    ) {
        return this.addIngredient(String.valueOf(identifier), source, toElement, executor);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            @NotNull String identifier,
            @NotNull Page<T> page,
            @NotNull Function<? super T, ? extends Element> toElement
    ) {
        return this.addIngredient(identifier, page.content(), toElement);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            char identifier,
            @NotNull Page<T> page,
            @NotNull Function<? super T, ? extends Element> toElement
    ) {
        return this.addIngredient(String.valueOf(identifier), page, toElement);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            @NotNull String identifier,
            @NotNull Page<T> page,
            @NotNull Function<? super T, ? extends Element> toElement,
            @NotNull Executor executor
    ) {
        return this.addIngredient(identifier, page.content(), toElement, executor);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            char identifier,
            @NotNull Page<T> page,
            @NotNull Function<? super T, ? extends Element> toElement,
            @NotNull Executor executor
    ) {
        return this.addIngredient(String.valueOf(identifier), page, toElement, executor);
    }

    @Override
    @NotNull
    public final B addIngredient(
            @NotNull String identifier,
            @NotNull Page<? extends Item> page
    ) {
        return this.addIngredient(identifier, page.content(), Element::item);
    }

    @Override
    @NotNull
    public final B addIngredient(
            char identifier,
            @NotNull Page<? extends Item> page
    ) {
        return this.addIngredient(String.valueOf(identifier), page);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            @NotNull String identifier,
            @NotNull Scroll<T> scroll,
            @NotNull Function<? super T, ? extends Element> toElement
    ) {
        return this.addIngredient(identifier, scroll, toElement, SlotProjection.defaultExecutor());
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            char identifier,
            @NotNull Scroll<T> scroll,
            @NotNull Function<? super T, ? extends Element> toElement
    ) {
        return this.addIngredient(String.valueOf(identifier), scroll, toElement);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            @NotNull String identifier,
            @NotNull Scroll<T> scroll,
            @NotNull Function<? super T, ? extends Element> toElement,
            @NotNull Executor executor
    ) {
        @SuppressWarnings("unchecked")
        Function<Object, ? extends Element> erased = (Function<Object, ? extends Element>) toElement;
        // 横着滚的内容按列切, 槽位也按列主序喂, 第 n 条才落在第 n 个列位上
        SlotPattern pattern = scroll.orientation() == Scroll.Orientation.HORIZONTAL ? SlotPatterns.COLUMN_MAJOR : null;
        return this.bindProjection(identifier, scroll.content(), erased, executor, pattern);
    }

    @Override
    @NotNull
    public final <T> B addIngredient(
            char identifier,
            @NotNull Scroll<T> scroll,
            @NotNull Function<? super T, ? extends Element> toElement,
            @NotNull Executor executor
    ) {
        return this.addIngredient(String.valueOf(identifier), scroll, toElement, executor);
    }

    @Override
    @NotNull
    public final B addIngredient(
            @NotNull String identifier,
            @NotNull Scroll<? extends Item> scroll
    ) {
        return this.addIngredient(identifier, scroll, Element::item);
    }

    @Override
    @NotNull
    public final B addIngredient(
            char identifier,
            @NotNull Scroll<? extends Item> scroll
    ) {
        return this.addIngredient(String.valueOf(identifier), scroll);
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull Tab<?> tab) {
        Objects.requireNonNull(tab, "tab");
        // 标志符先在这里解析.
        int identifierIndex = this.structure.identifierIndex(identifier);
        // 与其余 ingredient 同一套语义: 一个标志符只留最后声明的那一份
        this.tabIngredients[identifierIndex] = tab;
        this.ingredients[identifierIndex] = null;
        this.projections[identifierIndex] = null;
        return this.self();
    }

    @Override
    @NotNull
    public final B addIngredient(char identifier, @NotNull Tab<?> tab) {
        return this.addIngredient(String.valueOf(identifier), tab);
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull Pane pane) {
        return this.addIngredient(identifier, pane, 0, 0);
    }

    @Override
    @NotNull
    public final B addIngredient(@NotNull String identifier, @NotNull Pane pane, int offsetX, int offsetY) {
        return this.bindIngredient(identifier, ElementSupplier.pane(pane, offsetX, offsetY));
    }

    @Override
    @NotNull
    public final B setBackground(@Nullable ItemProvider background) {
        this.background = background;
        return this.self();
    }

    @Override
    @NotNull
    public final B setBackground(@NotNull ItemStack background) {
        return this.setBackground(ItemProvider.constant(background));
    }

    @Override
    @NotNull
    public final B setFrozen(boolean frozen) {
        this.frozen = frozen;
        return this.self();
    }

    @Override
    @NotNull
    public final B linkInventory(@NotNull SparrowInventory inventory) {
        Objects.requireNonNull(inventory);
        this.linkedInventories.add(inventory);
        return this.self();
    }

    @Override
    @NotNull
    public final B addModifier(@NotNull Consumer<? super G> modifier) {
        this.modifiers.add(modifier);
        return this.self();
    }

    @Override
    @NotNull
    public final B setModifiers(@NotNull List<? extends Consumer<? super G>> modifiers) {
        // 预先拒绝 null 修改器, 避免构建到一半才失败
        for (int i = 0; i < modifiers.size(); i++) {
            if (modifiers.get(i) == null) {
                throw new NullPointerException("modifiers must not contain null");
            }
        }
        this.modifiers.clear();
        this.modifiers.addAll(modifiers);
        return this.self();
    }

    @Override
    @NotNull
    public final B copy() {
        return this.newCopy();
    }

    @Override
    @NotNull
    public final G build() {
        // 先创建一份全空槽位数组
        Element[] elements = new Element[this.structure.size().area()];
        Arrays.fill(elements, Element.Empty.INSTANCE);

        // 按标志符编号生成槽位内容, 失败时附加模板位置
        for (int identifierIndex = 0; identifierIndex < this.ingredients.length; identifierIndex++) {
            ElementSupplier supplier = this.ingredients[identifierIndex];
            if (supplier == null) {
                continue;
            }

            SlotSequence slots = this.structure.slots(identifierIndex);
            int[] indices = slots.unsafeSlots();
            for (int occurrence = 0; occurrence < indices.length; occurrence++) {
                int slot = indices[occurrence];
                try {
                    Element element = Objects.requireNonNull(supplier.get(slots, occurrence), "ingredient");
                    elements[slot] = element;
                } catch (RuntimeException exception) {
                    throw this.instantiationFailure(identifierIndex, slot, exception);
                }
            }
        }

        // 所有槽位都生成成功后才创建 Pane, 然后挂投影, 最后执行修改器
        G pane = this.create(this.structure, elements, this.background, this.frozen);
        for (SparrowInventory inventory : this.linkedInventories) {
            pane.linkInventory(inventory);
        }
        // 投影就地求值一次, 因此 build 返回时这些槽位已经是序列当前的样子
        for (int identifierIndex = 0; identifierIndex < this.projections.length; identifierIndex++) {
            ProjectionIngredient projection = this.projections[identifierIndex];
            if (projection == null) {
                continue;
            }
            SlotSequence projected = this.structure.slots(identifierIndex);
            if (projection.pattern() != null) {
                projected = projected.transform(projection.pattern());
            }
            SlotProjection.attachErased(
                    pane,
                    projected,
                    projection.source(),
                    projection.toElement(),
                    projection.executor()
            );
        }
        // 标签组同样就地铺一轮, build 返回时区域已经是选中标签的样子
        for (int identifierIndex = 0; identifierIndex < this.tabIngredients.length; identifierIndex++) {
            Tab<?> tab = this.tabIngredients[identifierIndex];
            if (tab == null) {
                continue;
            }
            attachTab(pane, this.structure.slots(identifierIndex), tab);
        }
        for (Consumer<? super G> modifier : this.modifiers) {
            modifier.accept(pane);
        }
        return pane;
    }

    @NotNull
    protected abstract B self();

    @NotNull
    protected abstract B newCopy();

    @NotNull
    protected abstract G create(
            @NotNull Structure structure,
            Element @NotNull [] elements,
            @Nullable ItemProvider background,
            boolean frozen
    );

    /**
     * 将标志符转换为内部编号并保存绑定.
     *
     * @param identifier 标志符
     * @param supplier 元素生成器
     * @return 当前 Builder
     */
    private B bindIngredient(String identifier, ElementSupplier supplier) {
        int identifierIndex = this.structure.identifierIndex(identifier);
        this.ingredients[identifierIndex] = supplier;
        // 静态内容同样挤掉这个标志符上先声明的投影和标签组, 否则它们会在 build 末尾把它盖回去
        this.projections[identifierIndex] = null;
        this.tabIngredients[identifierIndex] = null;
        return this.self();
    }

    /**
     * 将标志符转换为内部编号并保存投影声明.
     *
     * @param identifier 标志符
     * @param source 序列来源
     * @param toElement 元素转换函数, 类型参数已擦除
     * @param executor 执行求值的执行器
     * @param pattern 槽位顺序变换, {@code null} 表示保持标志符原序
     * @return 当前 Builder
     */
    private B bindProjection(
            String identifier,
            Signal<? extends List<?>> source,
            Function<Object, ? extends Element> toElement,
            Executor executor,
            @Nullable SlotPattern pattern
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(toElement, "toElement");
        Objects.requireNonNull(executor, "executor");
        // 标志符先在这里解析.
        int identifierIndex = this.structure.identifierIndex(identifier);
        // 与其余 ingredient 同一套语义: 一个标志符只留最后声明的那一份, 顺手挤掉这个标志符上的静态内容和标签组
        this.projections[identifierIndex] = new ProjectionIngredient(source, toElement, executor, pattern);
        this.ingredients[identifierIndex] = null;
        this.tabIngredients[identifierIndex] = null;
        return this.self();
    }

    /**
     * 让一片槽位跟随标签组当前选中的子 Pane, 切换标签时整片重铺.
     * <p>绑定挂在宿主 Pane 上, 宿主经这条绑定持有铺放, 也随宿主一起结束.
     * 铺放是纯结构操作, 在切换标签的调用线程上同步完成.
     *
     * @param pane 接收铺放的宿主 Pane
     * @param slots 标签组负责的槽位
     * @param tab 标签组
     */
    private static void attachTab(AbstractPane pane, SlotSequence slots, Tab<?> tab) {
        Signal<Pane> selected = tab.pane();
        pane.bind(selected, host -> layTab(host, slots, selected.get()));
        // 就地铺一轮, 调用方拿到手时区域已经是对的
        layTab(pane, slots, selected.get());
    }

    /**
     * 把选中的子 Pane 铺进区域: 区域保持二维形状按相对坐标连接过去.
     * <p>子 Pane 盖不住的槽位补空; 内容没变的槽位不写, 因此切到已经选中的标签零写入.
     *
     * @param host 接收铺放的宿主 Pane
     * @param slots 本次要铺的槽位
     * @param selected 当前选中的子 Pane
     */
    private static void layTab(Pane host, SlotSequence slots, Pane selected) {
        PaneSize childSize = selected.size();
        int length = slots.length();
        for (int occurrence = 0; occurrence < length; occurrence++) {
            int childX = slots.xAt(occurrence) - slots.minX();
            int childY = slots.yAt(occurrence) - slots.minY();
            Element element = childX < childSize.width() && childY < childSize.height()
                    ? Element.PaneLink.trusted(selected, childSize.indexOfTrusted(childX, childY))
                    : Element.empty();
            int slot = slots.slotAt(occurrence);
            if (!element.equals(host.element(slot))) {
                host.setElement(slot, element);
            }
        }
    }

    /**
     * 一条投影声明: 跟随哪个序列, 怎么把序列里的一条数据变成 Element, 以及在哪里求值.
     * <p>它保存在与 {@code ingredients} 同下标的位置上, 因此是哪个标志符由下标决定.
     *
     * @param source 序列来源
     * @param toElement 元素转换函数, 类型参数已擦除
     * @param executor 执行求值的执行器
     * @param pattern 槽位顺序变换, {@code null} 表示保持标志符原序
     */
    private record ProjectionIngredient(
            Signal<? extends List<?>> source,
            Function<Object, ? extends Element> toElement,
            Executor executor,
            @Nullable SlotPattern pattern
    ) {
    }

    /**
     * 在 Supplier 异常中加入标志符, 行, 列和槽位编号, 便于定位模板问题.
     *
     * @param identifierIndex 标志符内部编号
     * @param slot 失败槽位编号
     * @param cause 原始异常
     * @return 带位置信息的构建失败异常
     */
    private IllegalStateException instantiationFailure(int identifierIndex, int slot, RuntimeException cause) {
        int width = this.structure.size().width();
        int row = slot / width;
        int column = slot % width;
        return new IllegalStateException(
                "failed to create identifier '" + this.structure.identifier(identifierIndex)
                        + "' at row " + (row + 1)
                        + ", source column " + this.structure.sourceColumn(slot)
                        + ", logical column " + (column + 1)
                        + ", slot " + slot,
                cause
        );
    }
}
