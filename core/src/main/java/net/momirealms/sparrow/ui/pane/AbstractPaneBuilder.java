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

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

abstract class AbstractPaneBuilder<G extends AbstractPane, B extends AbstractPaneBuilder<G, B>> implements Pane.Builder<G, B> {
    private final Structure structure;
    // 下面三个数组都用标志符编号当下标, 一格只会有其中一种声明
    private final ElementSupplier[] ingredients;       // 静态元素
    private final ProjectionIngredient[] projections;  // 跟着序列走的投影
    private final Tab<?>[] tabIngredients;             // 标签组
    private final ArrayList<Consumer<? super G>> modifiers;          // 建好 Pane 之后挨个跑一遍
    private final LinkedHashSet<SparrowInventory> linkedInventories; // 要关联到 Pane 上的 Inventory, 去重并保持声明顺序

    private ItemProvider background;  // 空槽位显示什么
    private boolean frozen;           // 建出来的 Pane 一开始冻不冻

    AbstractPaneBuilder(Structure structure) {
        this.structure = structure;
        this.ingredients = new ElementSupplier[structure.identifierCount()];
        this.projections = new ProjectionIngredient[structure.identifierCount()];
        this.tabIngredients = new Tab<?>[structure.identifierCount()];
        this.modifiers = new ArrayList<>();
        this.linkedInventories = new LinkedHashSet<>();
    }

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
    public final B addIngredient(char identifier, @NotNull ItemStack itemStack) {
        return this.addIngredient(String.valueOf(identifier), Item.simple(ItemProvider.constant(itemStack)));
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
        // 横滚的内容按列切片, 槽位就得按列主序摆; 竖滚本来就是行优先, 不用转
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
        int identifierIndex = this.structure.identifierIndex(identifier);
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
        // 整批先查 null, 有一个不合格就原样留着旧的那批
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
        // 先把 elements 数组铺满, 中间任何一个 supplier 抛了都不建 Pane
        Element[] elements = new Element[this.structure.size().area()];
        Arrays.fill(elements, Element.Empty.INSTANCE);

        // 静态元素这一轮是就地生成, 出错时报错要能指回模板的哪一格
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

        // 元素都齐了才建 Pane, 建完先把声明过的 Inventory 关联上
        G pane = this.create(this.structure, elements, this.background, this.frozen);
        for (SparrowInventory inventory : this.linkedInventories) {
            pane.linkInventory(inventory);
        }
        // 投影建出来会马上求值一轮, 所以 build 返回时这些槽位就是序列当前的样子
        for (int identifierIndex = 0; identifierIndex < this.projections.length; identifierIndex++) {
            ProjectionIngredient projection = this.projections[identifierIndex];
            if (projection == null) {
                continue;
            }
            SlotSequence projected = this.structure.slots(identifierIndex);
            if (projection.pattern() != null) {
                projected = projected.transform(projection.pattern());
            }
            SlotProjection.create(
                    pane,
                    projected,
                    projection.source(),
                    projection.toElement(),
                    projection.executor()
            );
        }
        // 标签组同样当场铺一遍, 铺的是当前选中的那个标签
        for (int identifierIndex = 0; identifierIndex < this.tabIngredients.length; identifierIndex++) {
            Tab<?> tab = this.tabIngredients[identifierIndex];
            if (tab == null) {
                continue;
            }
            attachTab(pane, this.structure.slots(identifierIndex), tab);
        }
        // 修改器放在最后, 它们想改的东西这时候都已经挂好了
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

    // 存静态元素声明, 顺手把同下标的另外两种清掉.
    private B bindIngredient(String identifier, ElementSupplier supplier) {
        int identifierIndex = this.structure.identifierIndex(identifier);
        this.ingredients[identifierIndex] = supplier;
        this.projections[identifierIndex] = null;
        this.tabIngredients[identifierIndex] = null;
        return this.self();
    }

    // 存投影声明, 同下标的另外两种一样要清掉.
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
        int identifierIndex = this.structure.identifierIndex(identifier);
        this.projections[identifierIndex] = new ProjectionIngredient(source, toElement, executor, pattern);
        this.ingredients[identifierIndex] = null;
        this.tabIngredients[identifierIndex] = null;
        return this.self();
    }

    // 订阅挂在宿主 Pane 上, 标签一换就在调用线程同步重铺; 寿命跟着 Pane 走, 不用使用方操心.
    private static void attachTab(AbstractPane pane, SlotSequence slots, Tab<?> tab) {
        Signal<Pane> selected = tab.pane();
        pane.bind(selected, host -> layTab(host, slots, selected.get()));
        layTab(pane, slots, selected.get());
    }

    // 按区域原来的二维形状接过去, 子 Pane 盖不到的位置补空
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

    // 一条投影声明: 序列来源, 怎么变成 Element, 在哪儿求值, 要不要换个槽位顺序.
    // 它和静态元素放在同一个下标上, 所以自己是哪个标志符不用记.
    private record ProjectionIngredient(
            Signal<? extends List<?>> source,
            Function<Object, ? extends Element> toElement,
            Executor executor,
            @Nullable SlotPattern pattern
    ) {
    }

    // 报错时把位置凑齐: 哪个标志符, 模板的第几行第几列, 以及槽号
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
