package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.window.click.EnchantSelectClick;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.window.handle.EnchantmentMenuHandle;
import net.momirealms.sparrow.ui.window.handle.MenuFactory;
import net.momirealms.sparrow.ui.window.handle.MenuInput;
import net.momirealms.sparrow.ui.util.HandlerList;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class EnchantmentWindowImpl extends AbstractWindow<EnchantmentMenuHandle> implements EnchantmentWindow {
    private static final int OPTION_COUNT = 3; // 原版附魔台协议固定三个按钮

    private final HandlerList<Consumer<EnchantSelectClick>> enchantSelectHandlers;
    private volatile EnchantOption[] options; // 最近在实体线程发布的写时复制选项快照
    private volatile int enchantmentSeed;

    EnchantmentWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            EnchantOption @NotNull [] options,
            int enchantmentSeed,
            @NotNull List<Consumer<EnchantSelectClick>> enchantSelectHandlers
    ) {
        super(manager, viewer, layout, settings);
        this.options = options.clone();
        this.enchantmentSeed = enchantmentSeed;
        this.enchantSelectHandlers = new HandlerList<>(enchantSelectHandlers);
    }

    @Override
    public void setOption(int index, @Nullable EnchantOption option) {
        EnchantmentWindowImpl.checkOptionIndex(index);
        this.submit(
                () -> {
                    EnchantOption[] current = this.options;
                    if (Objects.equals(current[index], option)) {
                        return;
                    }
                    // 先让已打开的协议 handle 接受新值, 再发布可供其他线程读取的 Window 快照
                    EnchantmentMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setOption(index, option);
                    }
                    EnchantOption[] next = current.clone();
                    next[index] = option;
                    this.options = next;
                    if (menuHandle != null) {
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update Enchantment Window option"
        );
    }

    @Override
    @Nullable
    public EnchantOption getOption(int index) {
        EnchantmentWindowImpl.checkOptionIndex(index);
        return this.options[index];
    }

    @Override
    public void setEnchantmentSeed(int seed) {
        this.submit(
                () -> {
                    if (this.enchantmentSeed == seed) {
                        return;
                    }
                    // 保持 handle 数据和公开状态的发布顺序一致
                    EnchantmentMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setEnchantmentSeed(seed);
                    }
                    this.enchantmentSeed = seed;
                    if (menuHandle != null) {
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update Enchantment Window seed"
        );
    }

    @Override
    public int getEnchantmentSeed() {
        return this.enchantmentSeed;
    }

    @Override
    public void setEnchantSelectHandlers(@NotNull List<? extends Consumer<? super EnchantSelectClick>> handlers) {
        List<Consumer<EnchantSelectClick>> copy = HandlerList.copyConsumers(handlers);
        this.submit(
                () -> this.enchantSelectHandlers.set(copy),
                "Failed to replace Enchantment Window enchant-select handlers"
        );
    }

    @Override
    @NotNull
    public List<Consumer<EnchantSelectClick>> getEnchantSelectHandlers() {
        return this.enchantSelectHandlers.snapshot();
    }

    @Override
    public void addEnchantSelectHandler(@NotNull Consumer<? super EnchantSelectClick> handler) {
        Consumer<EnchantSelectClick> copied = HandlerList.narrowConsumer(Objects.requireNonNull(handler, "handler"));
        this.submit(
                () -> this.enchantSelectHandlers.append(copied),
                "Failed to add Enchantment Window enchant-select handler"
        );
    }

    @Override
    public void removeEnchantSelectHandler(@NotNull Consumer<? super EnchantSelectClick> handler) {
        Consumer<EnchantSelectClick> copied = HandlerList.narrowConsumer(Objects.requireNonNull(handler, "handler"));
        this.submit(
                () -> this.enchantSelectHandlers.remove(copied),
                "Failed to remove Enchantment Window enchant-select handler"
        );
    }

    @Override
    @NotNull
    protected EnchantmentMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        EnchantmentMenuHandle menuHandle = factory.enchantment(this.viewer(), generation);
        // 使用同一个数组快照初始化三个选项, 避免并发读取跨越两版状态
        EnchantOption[] snapshot = this.options;
        for (int index = 0; index < OPTION_COUNT; index++) {
            menuHandle.setOption(index, snapshot[index]);
        }
        menuHandle.setEnchantmentSeed(this.enchantmentSeed);
        return menuHandle;
    }

    @Override
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
        if (!(input instanceof MenuInput.WindowSpecific.ButtonClick(int containerId, int button))) {
            return;
        }
        EnchantmentMenuHandle menuHandle = this.menuHandle();
        if (menuHandle == null || containerId != menuHandle.containerId() || button < 0 || button >= OPTION_COUNT) {
            return;
        }

        EnchantOption option = this.options[button];
        if (option == null) {
            return;
        }
        // 捕获按钮对应的非空选项, 后续处理器重入不会改变本轮参数
        EnchantSelectClick click = new EnchantSelectClick(this.viewer(), this, button, option);
        this.enchantSelectHandlers.forEachIsolated(
                handler -> handler.accept(click),
                "Failed to handle Enchantment Window selection",
                this::report
        );
    }

    private static void checkOptionIndex(int index) {
        if (index < 0 || index >= OPTION_COUNT) {
            throw new IndexOutOfBoundsException("enchantment option index out of bounds: " + index);
        }
    }

    static final class BuilderImpl extends AbstractWindowBuilder<EnchantmentWindow, EnchantmentWindow.Builder> implements EnchantmentWindow.Builder {
        private Pane upperPane = Pane.empty(new PaneSize(2, 1));
        private @Nullable Pane lowerPane;
        private EnchantOption[] options = new EnchantOption[OPTION_COUNT]; // 三个初始按钮, null 表示禁用
        private int enchantmentSeed;
        private List<Consumer<EnchantSelectClick>> enchantSelectHandlers = new ArrayList<>();

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperPane = source.upperPane;
            this.lowerPane = source.lowerPane;
            this.options = source.options.clone();
            this.enchantmentSeed = source.enchantmentSeed;
            this.enchantSelectHandlers = new ArrayList<>(source.enchantSelectHandlers);
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder setUpperPane(@NotNull Pane upperPane) {
            this.upperPane = Objects.requireNonNull(upperPane, "upperPane");
            return this;
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            return this;
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder setOption(int index, @Nullable EnchantOption option) {
            EnchantmentWindowImpl.checkOptionIndex(index);
            this.options[index] = option;
            return this;
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder setEnchantmentSeed(int seed) {
            this.enchantmentSeed = seed;
            return this;
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder setEnchantSelectHandlers(@NotNull List<? extends Consumer<? super EnchantSelectClick>> handlers) {
            this.enchantSelectHandlers = new ArrayList<>(HandlerList.copyConsumers(handlers));
            return this;
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder addEnchantSelectHandler(@NotNull Consumer<? super EnchantSelectClick> handler) {
            this.enchantSelectHandlers.add(HandlerList.narrowConsumer(Objects.requireNonNull(handler, "handler")));
            return this;
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected EnchantmentWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected EnchantmentWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.upperPane.width() != 2 || this.upperPane.height() != 1)
                throw new IllegalArgumentException("enchantment upper Pane must have size 2x1");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.upperPane),
                    WindowLayout.Region.lower(lowerPane)
            );
            return new EnchantmentWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.options,
                    this.enchantmentSeed,
                    List.copyOf(this.enchantSelectHandlers)
            );
        }
    }
}
