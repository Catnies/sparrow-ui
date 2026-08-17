package net.momirealms.sparrow.ui.item.provider;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
interface ImmediateItemProvider extends ItemProvider {

    @NotNull
    ItemStack provideImmediately(@NotNull RenderContext context);

    @Override
    @NotNull
    default CompletableFuture<? extends ItemStack> provide(@NotNull RenderContext context) {
        return CompletableFuture.completedFuture(Objects.requireNonNull(this.provideImmediately(context), "rendered item"));
    }
}
