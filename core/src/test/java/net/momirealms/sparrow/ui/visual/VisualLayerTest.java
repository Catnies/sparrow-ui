package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class VisualLayerTest {

    @Test
    void layerWithoutVisualizerKeepsNoPlaceholder() {
        VisualLayer layer = new VisualLayer(null, placeholder());

        assertNull(layer.placeholder(), "没有映射的层不该扣着占位不放");
        assertNull(layer.visualize(null), "没有映射的层一律放行");
    }

    @Test
    void repeatedEmptyLayerLeavesTheSlotVisualAlone() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        AtomicInteger dirties = new AtomicInteger();
        Subscription attachment = visual.attach(0, dirties::incrementAndGet);
        visual.setVisualizerProvider(null, placeholder());
        visual.setVisualizerProvider(0, null, placeholder());

        assertEquals(0, dirties.get(), "空层重设什么都没改变, 不该标脏");
        assertNull(visual.visualizerProvider());
        assertNull(visual.visualizerProvider(0));
        Reference.reachabilityFence(attachment);
    }

    @Test
    void repeatedEmptyLayerLeavesTheCursorAlone() {
        CursorVisualImpl cursor = new CursorVisualImpl(new Bindings(), VisualLayer.NONE);
        cursor.setVisualizerProvider(null, placeholder());

        assertFalse(cursor.takeDirty(), "空层重设什么都没改变, 不该标脏");
        assertNull(cursor.visualizerProvider());
    }

    @Test
    void repeatedItemVisualizerLeavesTheVisualAlone() {
        InventoryVisualImpl visual = new InventoryVisualImpl(new Bindings(), 1);
        AtomicInteger dirties = new AtomicInteger();
        Subscription attachment = visual.attach(0, dirties::incrementAndGet);
        Function<ItemStack, ItemStack> mapping = actual -> actual;
        visual.setVisualizerItem(mapping);

        assertEquals(1, dirties.get(), "第一次设置是真的换了配置");
        visual.setVisualizerItem(mapping);

        assertEquals(1, dirties.get(), "同一个 Item 映射重设不算换配置");
        Reference.reachabilityFence(attachment);
    }

    private static ImmediateItemProvider placeholder() {
        return ignoredContext -> {
            throw new AssertionError("占位只用来比对身份, 不该被求值");
        };
    }
}
