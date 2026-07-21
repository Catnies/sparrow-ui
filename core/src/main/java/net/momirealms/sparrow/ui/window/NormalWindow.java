package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.ClickEvent;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 普通箱子协议 Window. Normal、Split 与 Merged 只由 WindowLayout 路由区别.
 * 协议生命周期、点击分发和状态同步均继承自 {@link AbstractWindow}.
 */
final class NormalWindow extends AbstractWindow {

    NormalWindow(
            WindowManager manager,
            Player viewer,
            WindowLayout layout,
            Supplier<? extends Component> titleSupplier,
            boolean closeable,
            List<Runnable> openHandlers,
            List<Consumer<InventoryCloseEvent.Reason>> closeHandlers,
            List<Consumer<ClickEvent>> outsideClickHandlers,
            Supplier<? extends @Nullable Window> fallbackWindow,
            int windowState,
            List<Consumer<Integer>> windowStateChangeHandlers,
            Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer
    ) {
        super(
                manager,
                viewer,
                layout,
                titleSupplier,
                closeable,
                openHandlers,
                closeHandlers,
                outsideClickHandlers,
                fallbackWindow,
                windowState,
                windowStateChangeHandlers,
                cursorVisualizer
        );
    }
}
