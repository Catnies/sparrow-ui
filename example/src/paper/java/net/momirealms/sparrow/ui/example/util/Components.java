package net.momirealms.sparrow.ui.example.util;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class Components {
    private Components() {
    }

    @NotNull
    public static Component asAdventure(@NotNull net.minecraft.network.chat.Component component) {
        return PaperAdventure.asAdventure(component);
    }

    @NotNull
    public static Component entityName(@NotNull Entity entity) {
        Component customName = entity.customName();
        return customName != null ? customName : Component.translatable(entity.getType().translationKey());
    }

    public static void sendMessage(@NotNull Player player, @NotNull Component message) {
        player.sendMessage(message);
    }

    public static void playSound(@NotNull Player player, @NotNull org.bukkit.Sound type, float volume, float pitch) {
        player.playSound(Sound.sound(type, Sound.Source.MASTER, volume, pitch));
    }
}
