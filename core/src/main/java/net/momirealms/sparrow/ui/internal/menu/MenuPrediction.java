package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 交互消息携带的客户端预测状态.
 * <p>此接口刻意不暴露 NMS 类型, 使 Window 与交互解释器可在不加载服务端实现类的环境中运行.
 * 生产环境的 Paper Adapter 只识别自身创建的预测实现.
 */
@ApiStatus.Internal
public interface MenuPrediction {

    /**
     * 返回不携带任何客户端预测状态的占位对象.
     *
     * @return 空预测
     */
    static @NotNull MenuPrediction empty() {
        return Empty.INSTANCE;
    }

    enum Empty implements MenuPrediction {
        INSTANCE
    }
}
