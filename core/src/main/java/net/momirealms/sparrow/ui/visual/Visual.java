package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.NotNull;

public interface Visual {

    /**
     * 把这份配置标脏, 让宿主重新算一遍显示.
     */
    void dirty();

    /**
     * 让这个 Signal 每次失效都把这份配置标脏.
     *
     * @param signal 失效来源
     * @return 提前解绑用的句柄; <strong>丢掉它不等于退订</strong>, 绑定由宿主自己持有
     */
    @NotNull
    Subscription bind(@NotNull Signal<?> signal);
}
