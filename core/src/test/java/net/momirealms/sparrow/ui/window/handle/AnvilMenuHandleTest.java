package net.momirealms.sparrow.ui.window.handle;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.momirealms.sparrow.ui.window.handle.AnvilMenuHandleImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class AnvilMenuHandleTest {

    @Test
    void inputPlaceholderCarriesCurrentRenameWithoutMutatingSharedItem() {
        ItemStack shared = new ItemStack((ItemLike) null);
        shared.set(DataComponents.CUSTOM_NAME, Component.empty());
        AnvilMenuHandleImpl.InputPlaceholder placeholder = new AnvilMenuHandleImpl.InputPlaceholder(shared);

        assertSame(shared, placeholder.item());
        placeholder.renameText("stone");
        ItemStack first = (ItemStack) placeholder.item();

        assertNotSame(shared, first);
        assertSame(first, placeholder.item());
        assertEquals(Component.literal("stone"), first.component(DataComponents.CUSTOM_NAME));
        assertEquals(Component.empty(), shared.component(DataComponents.CUSTOM_NAME));
        placeholder.renameText(" ");
        ItemStack second = (ItemStack) placeholder.item();

        assertNotSame(first, second);
        assertEquals(Component.literal(" "), second.component(DataComponents.CUSTOM_NAME));
        assertEquals(Component.literal("stone"), first.component(DataComponents.CUSTOM_NAME));
        placeholder.renameText("");

        assertSame(shared, placeholder.item());
    }
}
