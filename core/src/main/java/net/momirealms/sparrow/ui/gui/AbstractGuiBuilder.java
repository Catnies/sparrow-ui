package net.momirealms.sparrow.ui.gui;

import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemBuilder;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * GUI Builder 的通用实现.
 * <p>它记录每个 Structure 标志符要填充的内容, 并统一处理背景,
 * 冻结状态, 构建后修改和失败位置诊断.
 */
abstract class AbstractGuiBuilder<G extends AbstractGui, B extends AbstractGuiBuilder<G, B>> implements Gui.Builder<G, B> {
    private final Structure structure;
    private final SlotElementSupplier[] ingredients; // 按 Structure 内部标志符编号保存绑定
    private final ArrayList<Consumer<? super G>> modifiers; // GUI 创建后按顺序执行

    private ItemProvider background;
    private boolean frozen;

    AbstractGuiBuilder(Structure structure) {
        this.structure = structure;
        this.ingredients = new SlotElementSupplier[structure.identifierCount()];
        this.modifiers = new ArrayList<>();
    }

    AbstractGuiBuilder(AbstractGuiBuilder<G, B> source) {
        this.structure = source.structure;
        this.ingredients = source.ingredients.clone();
        this.modifiers = new ArrayList<>(source.modifiers);
        this.background = source.background;
        this.frozen = source.frozen;
    }

    @Override
    public final @NotNull Structure structure() {
        return this.structure;
    }

    @Override
    public final @NotNull B addIngredient(@NotNull String identifier, @NotNull SlotElementSupplier supplier) {
        return this.bindIngredient(identifier, supplier);
    }

    @Override
    public final @NotNull B addIngredient(char identifier, @NotNull SlotElementSupplier supplier) {
        return this.addIngredient(String.valueOf(identifier), supplier);
    }

    @Override
    public final @NotNull B addIngredient(@NotNull String identifier, @NotNull SlotElement element) {
        return this.bindIngredient(identifier, SlotElementSupplier.fixed(element));
    }

    @Override
    public final @NotNull B addIngredient(char identifier, @NotNull SlotElement element) {
        return this.addIngredient(String.valueOf(identifier), element);
    }

    @Override
    public final @NotNull B addIngredient(@NotNull String identifier, @NotNull Item item) {
        return this.bindIngredient(identifier, SlotElementSupplier.fixed(new SlotElement.Item(item)));
    }

    @Override
    public final @NotNull B addIngredient(char identifier, @NotNull Item item) {
        return this.addIngredient(String.valueOf(identifier), item);
    }

    @Override
    public final @NotNull B addIngredient(@NotNull String identifier, @NotNull ItemBuilder itemBuilder) {
        return this.bindIngredient(identifier, (ignoredSize, ignoredOccurrence) -> new SlotElement.Item(itemBuilder.build()));
    }

    @Override
    public final @NotNull B addIngredient(@NotNull String identifier, @NotNull ItemProvider provider) {
        return this.addIngredient(identifier, Item.simple(provider));
    }

    @Override
    public final @NotNull B addIngredient(@NotNull String identifier, @NotNull ItemStack itemStack) {
        return this.addIngredient(identifier, Item.simple(ItemProvider.constant(itemStack)));
    }

    @Override
    public final @NotNull B addIngredient(@NotNull String identifier, @NotNull Supplier<? extends Item> itemSupplier) {
        return this.bindIngredient(identifier, SlotElementSupplier.items(itemSupplier));
    }

    @Override
    public final @NotNull B addIngredientElementSupplier(@NotNull String identifier, @NotNull Supplier<? extends SlotElement> elementSupplier) {
        return this.bindIngredient(identifier, SlotElementSupplier.fromSupplier(elementSupplier));
    }

    @Override
    public final @NotNull B addIngredient(@NotNull String identifier, @NotNull Gui gui) {
        return this.addIngredient(identifier, gui, 0, 0);
    }

    @Override
    public final @NotNull B addIngredient(@NotNull String identifier, @NotNull Gui gui, int offsetX, int offsetY) {
        return this.bindIngredient(identifier, SlotElementSupplier.gui(gui, offsetX, offsetY));
    }

    @Override
    public final @NotNull B setBackground(@Nullable ItemProvider background) {
        this.background = background;
        return this.self();
    }

    @Override
    public final @NotNull B setBackground(@NotNull ItemStack background) {
        return this.setBackground(ItemProvider.constant(background));
    }

    @Override
    public final @NotNull B setFrozen(boolean frozen) {
        this.frozen = frozen;
        return this.self();
    }

    @Override
    public final @NotNull B addModifier(@NotNull Consumer<? super G> modifier) {
        this.modifiers.add(modifier);
        return this.self();
    }

    @Override
    public final @NotNull B setModifiers(@NotNull List<? extends Consumer<? super G>> modifiers) {
        for (Consumer<? super G> modifier : modifiers) {
            if (modifier == null) {
                throw new NullPointerException("modifiers must not contain null");
            }
        }
        this.modifiers.clear();
        this.modifiers.addAll(modifiers);
        return this.self();
    }

    @Override
    public final @NotNull B copy() {
        return this.newCopy();
    }

    /**
     * 根据当前绑定创建 GUI. 任何 Supplier 失败时都不会返回半成品.
     */
    @Override
    public final @NotNull G build() {
        // 先创建一份全空槽位数组
        SlotElement[] elements = new SlotElement[this.structure.size().area()];
        Arrays.fill(elements, SlotElement.Empty.INSTANCE);

        // 按标志符编号生成槽位内容, 失败时附加模板位置
        for (int identifierIndex = 0; identifierIndex < this.ingredients.length; identifierIndex++) {
            SlotElementSupplier supplier = this.ingredients[identifierIndex];
            if (supplier == null) {
                continue;
            }

            SlotSequence slots = this.structure.slots(identifierIndex);
            int[] indices = slots.trustedArray();
            for (int occurrence = 0; occurrence < indices.length; occurrence++) {
                int slot = indices[occurrence];
                try {
                    SlotElement element = Objects.requireNonNull(supplier.get(slots, occurrence), "ingredient");
                    elements[slot] = element;
                } catch (RuntimeException exception) {
                    throw this.instantiationFailure(identifierIndex, slot, exception);
                }
            }
        }

        // 所有槽位都生成成功后才创建 GUI, 然后执行修改器
        G gui = this.create(this.structure, elements, this.background, this.frozen);
        for (Consumer<? super G> modifier : this.modifiers) {
            modifier.accept(gui);
        }
        return gui;
    }

    protected abstract @NotNull B self();

    protected abstract @NotNull B newCopy();

    protected abstract @NotNull G create(
            @NotNull Structure structure,
            SlotElement @NotNull [] elements,
            @Nullable ItemProvider background,
            boolean frozen
    );

    /**
     * 将标志符转换为内部编号并保存绑定.
     */
    private B bindIngredient(String identifier, SlotElementSupplier supplier) {
        this.ingredients[this.structure.identifierIndex(identifier)] = supplier;
        return this.self();
    }

    /**
     * 在 Supplier 异常中加入标志符, 行, 列和槽位编号, 便于定位模板问题.
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
