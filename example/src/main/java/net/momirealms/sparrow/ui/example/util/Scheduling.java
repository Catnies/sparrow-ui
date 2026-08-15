package net.momirealms.sparrow.ui.example.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class Scheduling {
    private Scheduling() {
    }

    @NotNull
    public static <T> CompletableFuture<T> async(@NotNull Plugin plugin, @NotNull Supplier<? extends T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, ignoredTask -> {
            try {
                future.complete(work.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }
}
