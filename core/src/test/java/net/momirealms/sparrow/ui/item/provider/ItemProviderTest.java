package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.window.RenderCell;
import net.momirealms.sparrow.ui.window.SparrowUiTestRuntime;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemProviderTest {

    private ServerMock server;
    private Player player;
    private RenderContext context;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        SparrowUiTestRuntime.installPlugin();
        SparrowUiTestRuntime.installOwnership(() -> false);
        player = server.addPlayer();
        Window window = window(player);
        context = new RenderContext(window, 4);
    }

    @AfterEach
    void tearDown() {
        SparrowUiTestRuntime.restoreOwnership();
        SparrowUiTestRuntime.restorePlugin();
        MockBukkit.unmock();
    }

    @Test
    void constantProviderOwnsItsTemplateAndReusesOneResult() {
        ItemStack input = new ItemStack(Material.DIAMOND, 3);
        ItemProvider provider = ItemProvider.constant(input);
        input.setAmount(1);
        CompletableFuture<ItemStack> firstFuture = provider.provide(context);
        CompletableFuture<ItemStack> secondFuture = provider.provide(context);
        ItemStack first = firstFuture.join();
        ItemStack second = secondFuture.join();

        assertNotSame(firstFuture, secondFuture);
        assertSame(first, second);
        assertEquals(3, second.getAmount());
    }

    @Test
    void lambdaProviderReceivesTheSlotRenderContext() {
        AtomicReference<RenderContext> received = new AtomicReference<>();
        ItemStack rendered = new ItemStack(Material.EMERALD, 5);
        ItemProvider provider = ItemProvider.sync(renderContext -> {
            received.set(renderContext);
            return rendered;
        });
        ItemStack result = provider.provide(context).join();

        assertSame(context, received.get());
        assertSame(rendered, result);
    }

    @Test
    void asynchronousAdapterRunsTheRendererThroughTheSharedExecutor() {
        AtomicReference<RenderContext> received = new AtomicReference<>();
        ItemStack rendered = new ItemStack(Material.GOLD_INGOT);
        ItemProvider provider = ItemProvider.async(renderContext -> {
            received.set(renderContext);
            return rendered;
        });
        CompletableFuture<ItemStack> result = provider.provide(this.context);

        assertEquals(1, SparrowUiTestRuntime.asyncRuns());
        assertSame(this.context, received.get());
        assertSame(rendered, result.join());
    }

    @Test
    void asynchronousRendererFailureCompletesTheFutureExceptionally() {
        RuntimeException failure = new RuntimeException("failure");
        ItemProvider provider = ItemProvider.async(ignoredContext -> {
            throw failure;
        });
        CompletionException thrown = assertThrows(CompletionException.class, () -> provider.provide(this.context).join());

        assertSame(failure, thrown.getCause());
    }

    @Test
    void synchronousAdapterBypassesFutureStateAndCompletesInTheSameRender() {
        AtomicInteger renders = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        ItemStack rendered = new ItemStack(Material.DIAMOND);
        ItemProvider provider = ItemProvider.sync(ignoredContext -> {
            renders.incrementAndGet();
            return rendered;
        });
        RenderCell cell = new RenderCell(this.context, invalidations::incrementAndGet, throwable -> { });
        RenderCell.Intent intent = new RenderCell.Intent.Projected(provider, null, ItemStack.empty());

        assertSame(rendered, cell.render(intent));
        assertSame(rendered, cell.render(intent));
        assertEquals(2, renders.get());
        assertEquals(0, invalidations.get());
        cell.close();
    }

    @Test
    void renderContextDerivesPlayerFromWindow() {
        Window window = window(player);

        assertSame(player, new RenderContext(window, 0).player());
    }

    @Test
    void renderContextRejectsNegativeWindowSlot() {
        Window window = window(player);

        assertThrows(IllegalArgumentException.class, () -> new RenderContext(window, -1));
    }

    private static Window window(Player viewer) {
        return new WindowStub(viewer);
    }
}
