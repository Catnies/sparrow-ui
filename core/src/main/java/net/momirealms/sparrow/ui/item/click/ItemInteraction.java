package net.momirealms.sparrow.ui.item.click;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface ItemInteraction {

    @NotNull
    Player player();

    @NotNull
    Window window();

    int windowSlot();

    /**
     * 这个槽位最近一次渲染记下的东西, 见 {@code RenderContext.remember}.
     *
     * @param <T> 期望的类型, 取值处转型.
     * @return 记下的东西, 没有时为 {@code null}
     */
    @Nullable
    @SuppressWarnings("unchecked")
    default <T> T remembered() {
        return (T) this.window().rememberedAt(this.windowSlot());
    }
}
