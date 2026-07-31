package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.click.EnchantSelectClick;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.EnchantmentMenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.util.MiscUtils;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class EnchantmentWindowImpl extends AbstractWindow<EnchantmentMenuHandle> implements EnchantmentWindow {
    private static final int OPTION_COUNT = 3; // 原版附魔台固定提供三个按钮

    private final HandlerList<Consumer<EnchantSelectClick>> enchantSelectionHandlers; // 按注册顺序分发的选择处理器
    private volatile EnchantOption[] options; // 最近在实体线程发布的写时复制选项快照
    private volatile int enchantmentSeed;     // 最近在实体线程应用的客户端符文种子

    EnchantmentWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            EnchantOption @NotNull [] options,
            int enchantmentSeed,
            @NotNull List<Consumer<EnchantSelectClick>> enchantSelectionHandlers
    ) {
        super(manager, viewer, layout, settings);
        this.options = options.clone();
        this.enchantmentSeed = enchantmentSeed;
        this.enchantSelectionHandlers = new HandlerList<>(enchantSelectionHandlers);
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
    public void setEnchantSelectionHandlers(@NotNull List<? extends Consumer<? super EnchantSelectClick>> handlers) {
        List<Consumer<EnchantSelectClick>> copy = MiscUtils.copyConsumers(handlers);
        this.submit(
                () -> this.enchantSelectionHandlers.set(copy),
                "Failed to replace Enchantment Window selection handlers"
        );
    }

    @Override
    @NotNull
    public List<Consumer<EnchantSelectClick>> getEnchantSelectionHandlers() {
        return this.enchantSelectionHandlers.snapshot();
    }

    @Override
    public void addEnchantSelectionHandler(@NotNull Consumer<? super EnchantSelectClick> handler) {
        Consumer<EnchantSelectClick> copied = MiscUtils.narrowConsumer(Objects.requireNonNull(handler, "handler"));
        this.submit(
                () -> this.enchantSelectionHandlers.append(copied),
                "Failed to add Enchantment Window selection handler"
        );
    }

    @Override
    public void removeEnchantSelectionHandler(@NotNull Consumer<? super EnchantSelectClick> handler) {
        Consumer<EnchantSelectClick> copied = MiscUtils.narrowConsumer(Objects.requireNonNull(handler, "handler"));
        this.submit(
                () -> this.enchantSelectionHandlers.remove(copied),
                "Failed to remove Enchantment Window selection handler"
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
        // 非按钮输入和过期、越界按钮都不属于当前附魔选择
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
        this.enchantSelectionHandlers.forEachIsolated(
                handler -> handler.accept(click),
                "Failed to handle Enchantment Window selection",
                this::report
        );
    }

    /**
     * 校验原版三个附魔按钮共用的索引范围.
     *
     * @param index 待校验索引
     * @throws IndexOutOfBoundsException 当索引不在 [0, 3) 时
     */
    private static void checkOptionIndex(int index) {
        if (index < 0 || index >= OPTION_COUNT) {
            throw new IndexOutOfBoundsException("enchantment option index out of bounds: " + index);
        }
    }

    static final class BuilderImpl extends AbstractWindowBuilder<EnchantmentWindow, EnchantmentWindow.Builder> implements EnchantmentWindow.Builder {
        private Gui upperGui = Gui.empty(new GuiSize(2, 1));
        private @Nullable Gui lowerGui;
        private EnchantOption[] options = new EnchantOption[OPTION_COUNT]; // 三个初始按钮, null 表示禁用
        private int enchantmentSeed;
        private List<Consumer<EnchantSelectClick>> enchantSelectionHandlers = new ArrayList<>();

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperGui = source.upperGui;
            this.lowerGui = source.lowerGui;
            this.options = source.options.clone();
            this.enchantmentSeed = source.enchantmentSeed;
            this.enchantSelectionHandlers = new ArrayList<>(source.enchantSelectionHandlers);
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = Objects.requireNonNull(upperGui, "upperGui");
            return this;
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
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
        public EnchantmentWindow.Builder setEnchantSelectionHandlers(@NotNull List<? extends Consumer<? super EnchantSelectClick>> handlers) {
            this.enchantSelectionHandlers = new ArrayList<>(MiscUtils.copyConsumers(handlers));
            return this;
        }

        @Override
        @NotNull
        public EnchantmentWindow.Builder addEnchantSelectionHandler(@NotNull Consumer<? super EnchantSelectClick> handler) {
            this.enchantSelectionHandlers.add(MiscUtils.narrowConsumer(Objects.requireNonNull(handler, "handler")));
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
            if (this.upperGui.width() != 2 || this.upperGui.height() != 1)
                throw new IllegalArgumentException("enchantment upper GUI must have size 2x1");

            this.lowerGui = this.lowerGui == null ? viewerReferencingInventory(viewer) : this.lowerGui;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.upperGui),
                    WindowLayout.Region.lower(this.lowerGui)
            );
            return new EnchantmentWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.options,
                    this.enchantmentSeed,
                    List.copyOf(this.enchantSelectionHandlers)
            );
        }
    }
}
