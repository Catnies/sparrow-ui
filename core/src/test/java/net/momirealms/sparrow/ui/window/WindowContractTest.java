package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaEntityExecutor;
import net.momirealms.sparrow.ui.window.click.EnchantSelectClick;
import net.momirealms.sparrow.ui.window.click.MerchantTradeSelectClick;
import net.momirealms.sparrow.ui.window.click.WindowOutsideClick;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowContractTest {

    @Test
    void lifecycleStageReportsCommittedResult() {
        Player player = player();
        Window window = new TestBuilder().build(player);

        assertSame(player, window.viewer());
        assertFalse(window.isOpen());
        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertTrue(window.isOpen());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        assertFalse(window.isOpen());
    }

    @Test
    void windowVisualizerBuilderFlowsIntoTheWindow() {
        Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider =
                ignoredActual -> ItemProvider.EMPTY;
        Window window = Window.builder(Pane.empty(9, 1))
                .setVisualizerProvider(visualizerProvider)
                .build(player());

        assertSame(visualizerProvider, window.visualizerProvider());
        assertSame(window.visual(), window.visual());
        assertNull(window.cursorVisualizerProvider());
    }

    @Test
    void cursorVisualizerBuilderSupportsProviderAndItemSugar() {
        Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider =
                ignoredActual -> ItemProvider.EMPTY;
        Window providerWindow = Window.builder(Pane.empty(9, 1))
                .setCursorVisualizerProvider(visualizerProvider)
                .build(player());

        assertSame(visualizerProvider, providerWindow.cursorVisualizerProvider());
        AtomicBoolean visualizerInvoked = new AtomicBoolean();
        Window sugarWindow = Window.builder(Pane.empty(9, 1))
                .setCursorVisualizerItem(ignoredActual -> {
                    visualizerInvoked.set(true);
                    return null;
                })
                .build(player());

        assertNull(sugarWindow.cursorVisualizerProvider().apply(null));
        assertTrue(visualizerInvoked.get());
    }

    @Test
    void cursorVisualizerBuilderCarriesADeferredMappingIntoTheWindow() {
        Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer =
                ignoredActual -> ignoredContext -> new CompletableFuture<>();
        Window window = Window.builder(Pane.empty(9, 1))
                .setCursorVisualizerProvider(visualizer, ItemProvider.EMPTY)
                .build(player());

        assertSame(visualizer, window.cursorVisualizerProvider());
    }

    @Test
    void publicContractOnlyExposesStagesForLifecycleCommands() throws ReflectiveOperationException {
        Method open = Window.class.getDeclaredMethod("open");
        Method close = Window.class.getDeclaredMethod("close");
        Method setTitle = Window.class.getDeclaredMethod("setTitle", Component.class);
        Method setCloseable = Window.class.getDeclaredMethod("setCloseable", boolean.class);
        Method backOnPlayerClose = Window.class.getDeclaredMethod("backOnPlayerClose", boolean.class);

        assertTrue(exposesFuture(open));
        assertTrue(exposesFuture(close));
        assertFalse(exposesFuture(setTitle));
        assertFalse(exposesFuture(setCloseable));
        assertFalse(exposesFuture(backOnPlayerClose));
        boolean exposesDirty = Arrays.stream(Window.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("dirty"));
        Method notifyUpdate = Window.class.getDeclaredMethod("notifyUpdate", int.class);

        assertFalse(exposesDirty);
        assertTrue(Modifier.isPublic(notifyUpdate.getModifiers()));
    }

    @Test
    void explicitWindowBuildersOwnTheirProtocolSizeConstraints() {
        Player player = player();

        assertThrows(
                IllegalArgumentException.class,
                () -> Window.builder(Pane.empty(5, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> HopperWindow.builder().setUpperPane(Pane.empty(9, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> AnvilWindow.builder().setUpperPane(Pane.empty(5, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> DispenserWindow.builder().setUpperPane(Pane.empty(9, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> DropperWindow.builder().setUpperPane(Pane.empty(9, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> GrindstoneWindow.builder().setInputPane(Pane.empty(2, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> GrindstoneWindow.builder().setResultPane(Pane.empty(2, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SmithingWindow.builder().setUpperPane(Pane.empty(3, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> BrewingWindow.builder().setInputPane(Pane.empty(2, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> BrewingWindow.builder().setFuelPane(Pane.empty(1, 2)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> BrewingWindow.builder().setResultPane(Pane.empty(2, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> CartographyWindow.builder().setInputPane(Pane.empty(2, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> CartographyWindow.builder().setResultPane(Pane.empty(1, 2)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> CrafterWindow.builder().setCraftingPane(Pane.empty(3, 2)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> CrafterWindow.builder().setResultPane(Pane.empty(1, 2)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> CraftingWindow.builder().setCraftingPane(Pane.empty(3, 2)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> CraftingWindow.builder().setResultPane(Pane.empty(1, 2)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> CraftingWindow.builder().setLowerPane(Pane.empty(9, 3)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> FurnaceWindow.builder().setInputPane(Pane.empty(2, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SmokerWindow.builder().setFuelPane(Pane.empty(1, 2)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> BlastFurnaceWindow.builder().setResultPane(Pane.empty(2, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> EnchantmentWindow.builder().setUpperPane(Pane.empty(3, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> EnchantmentWindow.builder().setLowerPane(Pane.empty(9, 3)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> StonecutterWindow.builder().setUpperPane(Pane.empty(3, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> StonecutterWindow.builder().setButtonsPane(Pane.empty(3, 1)).build(player)
        );

        assertThrows(
                NullPointerException.class,
                () -> StonecutterWindow.builder().setButtonsPane(null)
        );

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> StonecutterWindow.builder()
                        .setSelectedRecipeIndex(0)
                        .build(player)
        );
    }

    @Test
    void furnaceFamilyKeepsIndependentPublicTypes() {
        assertTrue(RecipeBookWindow.class.isAssignableFrom(FurnaceWindow.class));
        assertFalse(FurnaceWindow.class.isAssignableFrom(SmokerWindow.class));
        assertFalse(FurnaceWindow.class.isAssignableFrom(BlastFurnaceWindow.class));
    }

    @Test
    void reusableBuilderCreatesViewerSpecificDefaultLowerPane() {
        SparrowUiTestRuntime.install(new WindowManager(SparrowUiTestRuntime.plugin(), null, new FoliaEntityExecutor(SparrowUiTestRuntime.plugin())));
        HopperWindow.Builder builder = HopperWindow.builder();
        Player firstViewer = player();
        HopperWindow first = builder.build(firstViewer);
        HopperWindow second = builder.build(player());
        Pane customLowerPane = Pane.empty(9, 4);
        HopperWindow custom = builder.setLowerPane(customLowerPane).build(player());
        Pane mergedPane = Pane.empty(9, 6);
        NormalWindow merged = NormalWindow.mergedBuilder(mergedPane).build(player());

        assertSame(first.panes().get(0), second.panes().get(0));
        assertNotSame(first.panes().get(1), second.panes().get(1));
        assertSame(first.panes().get(1), first.lowerPane());
        ReferencingInventory firstDefaultLower = first.defaultLowerInventory();

        assertNotNull(firstDefaultLower);
        assertSame(firstViewer.getInventory(), firstDefaultLower.referencedInventory());
        assertSame(customLowerPane, custom.lowerPane());
        assertNull(custom.defaultLowerInventory());
        assertSame(mergedPane, merged.lowerPane());
        assertNull(merged.defaultLowerInventory());
    }

    @Test
    void stonecutterBuilderKeepsItsFixedWidthButtonsPaneAsALogicalRoot() {
        SparrowUiTestRuntime.install(new WindowManager(SparrowUiTestRuntime.plugin(), null, new FoliaEntityExecutor(SparrowUiTestRuntime.plugin())));
        Player player = player();
        Pane buttons = Pane.empty(4, 1);
        StonecutterWindow defaults = StonecutterWindow.builder().build(player);
        StonecutterWindow configured = StonecutterWindow.builder()
                .setButtonsPane(buttons)
                .setSelectedRecipeIndex(3)
                .build(player);

        assertEquals(3, defaults.panes().size());
        assertEquals(new PaneSize(4, 0), defaults.panes().get(2).size());
        assertSame(buttons, configured.panes().get(2));
        assertEquals(buttons, configured.paneAt(38).pane());
        assertEquals(3, configured.getSelectedRecipeIndex());
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> StonecutterWindow.builder()
                        .setButtonsPane(buttons)
                        .setSelectedRecipeIndex(4)
                        .build(player)
        );
    }

    @Test
    void enchantmentBuilderKeepsIndependentOptionAndHandlerSnapshots() {
        SparrowUiTestRuntime.install(new WindowManager(SparrowUiTestRuntime.plugin(), null, new FoliaEntityExecutor(SparrowUiTestRuntime.plugin())));
        Player player = player();
        EnchantmentWindow.EnchantOption first = new EnchantmentWindow.EnchantOption(1, null, 1);
        EnchantmentWindow.EnchantOption second = new EnchantmentWindow.EnchantOption(3, null, 7);
        Consumer<EnchantSelectClick> handler = ignoredClick -> {};
        EnchantmentWindow.Builder original = EnchantmentWindow.builder()
                .setOption(0, first)
                .setEnchantmentSeed(41)
                .addEnchantSelectHandler(handler);
        EnchantmentWindow built = original.build(player);
        EnchantmentWindow cloned = original.clone()
                .setOption(0, null)
                .setOption(1, second)
                .setEnchantmentSeed(73)
                .setEnchantSelectHandlers(List.of())
                .build(player);
        EnchantmentWindow defaults = EnchantmentWindow.builder().build(player);

        assertEquals(first, built.getOption(0));
        assertNull(built.getOption(1));
        assertEquals(41, built.getEnchantmentSeed());
        assertEquals(List.of(handler), built.getEnchantSelectHandlers());
        assertNull(cloned.getOption(0));
        assertEquals(second, cloned.getOption(1));
        assertEquals(73, cloned.getEnchantmentSeed());
        assertTrue(cloned.getEnchantSelectHandlers().isEmpty());
        assertNull(defaults.getOption(0));
        assertEquals(0, defaults.getEnchantmentSeed());
        assertEquals(0, new EnchantmentWindow.EnchantOption(0, null, 1).cost());
        assertThrows(IndexOutOfBoundsException.class, () -> original.setOption(-1, first));
        assertThrows(IndexOutOfBoundsException.class, () -> built.getOption(3));
        assertThrows(NullPointerException.class, () -> EnchantmentWindow.builder().setUpperPane(null));
        assertThrows(NullPointerException.class, () -> EnchantmentWindow.builder().addEnchantSelectHandler(null));
    }

    @Test
    void replacingBuilderHandlersStillAllowsAppendingHandlers() {
        assertDoesNotThrow(() -> NormalWindow.builder()
                .setOpenHandlers(List.of())
                .addOpenHandler(ignoredWindow -> {})
                .setCloseHandlers(List.of())
                .addCloseHandler((ignoredWindow, ignoredReason) -> {}));

        assertDoesNotThrow(() -> AnvilWindow.builder()
                .setRenameHandlers(List.of())
                .addRenameHandler(ignoredName -> {}));

        assertDoesNotThrow(() -> CrafterWindow.builder()
                .setSlotToggleHandlers(List.of())
                .addSlotToggleHandler((ignoredSlot, ignoredState) -> {}));

        assertDoesNotThrow(() -> CraftingWindow.builder()
                .setRecipeSelectHandlers(List.of())
                .addRecipeSelectHandler(ignoredSelection -> {})
                .clone()
                .addRecipeSelectHandler(ignoredSelection -> {}));

        assertDoesNotThrow(() -> MerchantWindow.builder()
                .setTradeSelectHandlers(List.of())
                .addTradeSelectHandler(ignoredSelect -> {})
                .clone()
                .addTradeSelectHandler(ignoredSelect -> {}));
    }

    @Test
    void merchantBuilderAndMutableContractValidateBeforeDispatch() {
        SparrowUiTestRuntime.install(new WindowManager(SparrowUiTestRuntime.plugin(), null, new FoliaEntityExecutor(SparrowUiTestRuntime.plugin())));
        Player player = player();
        MerchantWindow.Trade trade = MerchantWindow.Trade.builder().build();
        Consumer<MerchantTradeSelectClick> handler = ignoredSelection -> {};
        MerchantWindow.Builder original = MerchantWindow.builder()
                .setLevel(2)
                .setProgress(0.5)
                .setRestockMessageEnabled(true)
                .setTrades(List.of(trade))
                .addTradeSelectHandler(handler);
        MerchantWindow built = original.build(player);
        MerchantWindow cloned = original.clone()
                .setLevel(5)
                .setProgress(-1.0)
                .setTrades(List.of())
                .build(player);
        MerchantWindow defaults = MerchantWindow.builder().build(player);

        assertEquals(2, built.getLevel());
        assertEquals(0.5, built.getProgress());
        assertTrue(built.isRestockMessageEnabled());
        assertEquals(List.of(trade), built.getTrades());
        assertEquals(List.of(handler), built.getTradeSelectHandlers());
        assertEquals(5, cloned.getLevel());
        assertEquals(-1.0, cloned.getProgress());
        assertTrue(cloned.getTrades().isEmpty());
        assertEquals(0, defaults.getLevel());
        assertEquals(-1.0, defaults.getProgress());
        assertFalse(defaults.isRestockMessageEnabled());
        assertTrue(defaults.getTrades().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> built.getTrades().add(trade));
        assertThrows(
                UnsupportedOperationException.class,
                () -> built.getTradeSelectHandlers().add(handler)
        );

        assertThrows(IllegalArgumentException.class, () -> MerchantWindow.builder().setLevel(-1));
        assertThrows(IllegalArgumentException.class, () -> MerchantWindow.builder().setLevel(6));
        assertThrows(IllegalArgumentException.class, () -> MerchantWindow.builder().setProgress(-0.01));
        assertThrows(IllegalArgumentException.class, () -> MerchantWindow.builder().setProgress(1.01));
        assertThrows(IllegalArgumentException.class, () -> MerchantWindow.builder().setProgress(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> MerchantWindow.builder().setProgress(Double.POSITIVE_INFINITY));
        assertThrows(NullPointerException.class, () -> MerchantWindow.builder().setUpperPane(null));
        assertThrows(NullPointerException.class, () -> MerchantWindow.builder().setTrades(null));
        assertThrows(
                NullPointerException.class,
                () -> MerchantWindow.builder().setTrades(Collections.singletonList(null))
        );

        assertThrows(NullPointerException.class, () -> MerchantWindow.builder().addTradeSelectHandler(null));
        assertThrows(NullPointerException.class, () -> MerchantWindow.Trade.builder().setFirstInput(null));
        assertThrows(NullPointerException.class, () -> MerchantWindow.Trade.builder().setSecondInput(null));
        assertThrows(NullPointerException.class, () -> MerchantWindow.Trade.builder().setResult(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> MerchantWindow.builder().setUpperPane(Pane.empty(2, 1)).build(player)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MerchantWindow.builder().setLowerPane(Pane.empty(9, 3)).build(player)
        );

        assertThrows(IllegalArgumentException.class, () -> built.setLevel(-1));
        assertThrows(IllegalArgumentException.class, () -> built.setProgress(Double.NaN));
        assertThrows(NullPointerException.class, () -> built.setTrades(null));
        assertThrows(
                NullPointerException.class,
                () -> built.setTrades(Collections.singletonList(null))
        );

        assertThrows(NullPointerException.class, () -> built.addTradeSelectHandler(null));
        assertThrows(NullPointerException.class, () -> built.removeTradeSelectHandler(null));
        assertSame(Item.empty(), trade.getFirstInput());
    }

    private static boolean exposesFuture(Method method) {
        boolean hasStageParameter = Arrays.stream(method.getParameterTypes())
                .anyMatch(CompletableFuture.class::equals);
        return CompletableFuture.class.equals(method.getReturnType()) || hasStageParameter;
    }

    private static Player player() {
        UUID playerId = UUID.randomUUID();
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                WindowContractTest.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getStorageContents" -> new ItemStack[36];
                    case "getMaxStackSize" -> 64;
                    case "getHolder" -> null;
                    case "getLocation" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "WindowContractTestInventory";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        return (Player) Proxy.newProxyInstance(
                WindowContractTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getInventory" -> inventory;
                    case "isValid", "isConnected", "isSleeping" -> false;
                    case "hashCode" -> playerId.hashCode();
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "WindowContractPlayerStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static final class TestBuilder implements Window.Builder<Window, TestBuilder> {
        @Override
        public @NotNull TestBuilder setViewer(@NotNull Player viewer) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setTitleSupplier(
                @NotNull Supplier<? extends Component> titleSupplier
        ) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setTitle(@NotNull Component title) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setCloseable(boolean closeable) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setOpenHandlers(@NotNull List<? extends Consumer<? super Window>> openHandlers) {
            return this;
        }
        @Override
        public @NotNull TestBuilder addOpenHandler(@NotNull Consumer<? super Window> handler) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setCloseHandlers(
                @NotNull List<? extends BiConsumer<? super Window, ? super WindowCloseReason>> closeHandlers
        ) {
            return this;
        }
        @Override
        public @NotNull TestBuilder addCloseHandler(
                @NotNull BiConsumer<? super Window, ? super WindowCloseReason> handler
        ) {
            return this;
        }
        @Override
        @NotNull
        public TestBuilder setOutsideClickHandlers(
                @NotNull List<? extends BiConsumer<? super Window, ? super WindowOutsideClick>> outsideClickHandlers
        ) {
            return this;
        }
        @Override
        public @NotNull TestBuilder addOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> handler) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setBackOnPlayerClose(boolean backOnPlayerClose) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setData(@NotNull Object data) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setSessionKind(@NotNull WindowSession.Kind kind) {
            return this;
        }
        @Override
        public @NotNull TestBuilder addSessionEndHandler(@NotNull Consumer<? super WindowCloseReason> handler) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setWindowState(int windowState) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setWindowStateChangeHandlers(
                @NotNull List<? extends Consumer<? super Integer>> handlers
        ) {
            return this;
        }
        @Override
        public @NotNull TestBuilder addWindowStateChangeHandler(
                @NotNull Consumer<? super Integer> handler
        ) {
            return this;
        }
        @Override
        @NotNull
        public TestBuilder setVisualizerProvider(
                @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
                @Nullable ImmediateItemProvider placeholder
        ) {
            return this;
        }
        @Override
        @NotNull
        public TestBuilder setCursorVisualizerProvider(
                @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizerProvider,
                @Nullable ImmediateItemProvider placeholder
        ) {
            return this;
        }
        @Override
        public @NotNull TestBuilder setModifiers(
                @NotNull List<? extends Consumer<? super Window>> modifiers
        ) {
            return this;
        }
        @Override
        public @NotNull TestBuilder addModifier(@NotNull Consumer<? super Window> modifier) {
            return this;
        }
        @Override
        public @NotNull TestBuilder clone() {
            return new TestBuilder();
        }
        @Override
        public @NotNull Window build() {
            throw new IllegalStateException("viewer has not been set");
        }
        @Override
        public @NotNull Window build(@NotNull Player viewer) {
            return new WindowStub(viewer);
        }
    }
}
