package net.momirealms.sparrow.ui.item.click;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public interface ItemInteraction {

    @NotNull
    Player player();

    @NotNull
    Window window();

    int windowSlot();
}
