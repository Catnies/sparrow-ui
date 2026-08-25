package net.momirealms.sparrow.ui.window.handle;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 在 Window 层传递客户端预测, 不向上暴露 NMS 类型.
 * <p>Paper 适配器只接收自己创建的预测实现.
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
