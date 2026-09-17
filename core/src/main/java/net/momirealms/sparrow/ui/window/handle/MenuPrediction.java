package net.momirealms.sparrow.ui.window.handle;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 把客户端预测传到 Window 这一层用的载体, 免得把 NMS 类型往外露.
 * <p>Paper 那边的适配器只认自己创建出来的预测实现.
 */
@ApiStatus.Internal
public interface MenuPrediction {

    static @NotNull MenuPrediction empty() {
        return Empty.INSTANCE;
    }

    enum Empty implements MenuPrediction {
        INSTANCE
    }
}
