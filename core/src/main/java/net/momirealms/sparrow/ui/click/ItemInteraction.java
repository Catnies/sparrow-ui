package net.momirealms.sparrow.ui.click;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Item 接收的一次玩家交互所共有的上下文.
 */
public interface ItemInteraction {

    @NotNull
    Player player();

    @NotNull
    Window window();

    int windowSlot();
}
