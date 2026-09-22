package net.momirealms.sparrow.ui.example.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.sparrow.ui.util.AdventureUtils;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftChatMessage;
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
        return GsonComponentSerializer.gson().deserialize(CraftChatMessage.toJSON(component));
    }

    @NotNull
    public static Component entityName(@NotNull Entity entity) {
        return asAdventure(((CraftEntity) entity).getHandle().getName());
    }

    public static void sendMessage(@NotNull Player player, @NotNull Component message) {
        ((CraftPlayer) player).getHandle().sendSystemMessage((net.minecraft.network.chat.Component) AdventureUtils.asVanilla(message));
    }

    public static void playSound(@NotNull Player player, @NotNull Sound type, float volume, float pitch) {
        player.playSound(player, type, SoundCategory.MASTER, volume, pitch);
    }
}
