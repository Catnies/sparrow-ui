package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemBuilder;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pane Builder 的通用实现.
 * <p>它记录每个 Structure 标志符要填充的内容, 并统一处理背景,
 * 冻结状态, 构建后修改和失败位置诊断.
 */
abstract class AbstractPaneBuilder<G extends AbstractPane, B extends AbstractPaneBuilder<G, B>> implements Pane.Builder<G, B> {
    private final Structure structure; // Pane 布局
    private final ElementSupplier[] ingredients; // 按 Structure 内部标志符编号保存绑定
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

        // 所有槽位都生成成功后才创建 Pane, 然后执行修改器
        G pane = this.create(this.structure, elements, this.background, this.frozen);
        for (SparrowInventory inventory : this.linkedInventories) {
            pane.linkInventory(inventory);
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
        this.ingredients[this.structure.identifierIndex(identifier)] = supplier;
        return this.self();
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
