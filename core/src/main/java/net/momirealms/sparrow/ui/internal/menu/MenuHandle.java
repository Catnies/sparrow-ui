package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Window 引擎与 Paper/NMS 菜单实现之间的窄边界.
 */
@ApiStatus.Internal
public interface MenuHandle extends AutoCloseable {

    /**
     * 菜单关闭的触发来源.
     */
    enum CloseMode {
        PLUGIN,
        CLIENT,
        REPLACED
    }

    /**
     * 返回此会话的 Minecraft 容器编号.
     *
     * @return 容器编号
     */
    int containerId();

    /**
     * 返回供 Bukkit 事件读取的协议视图.
     *
     * @return 当前菜单视图
     */
    @NotNull InventoryView view();

    /**
     * 打开菜单并发送初始完整状态.
     *
     * @param title 初始标题
     * @param initialState 初始完整物品与光标状态
     */
    void open(@NotNull Component title, @NotNull SyncPlan.Full initialState);

    /**
     * 发送已准备好的状态同步计划.
     *
     * @param plan 要发送的同步计划
     */
    void send(@NotNull SyncPlan plan);

    /**
     * 用重新打开界面和完整状态更新客户端标题.
     *
     * @param title 新标题
     * @param fullState 与新标题一同重发的完整状态
     */
    void updateTitle(@NotNull Component title, @NotNull SyncPlan.Full fullState);

    /**
     * 向客户端发送用于 Window 状态确认的协议 Ping.
     *
     * @param id Ping 标识
     */
    void sendPing(int id);

    /**
     * 清理菜单资源.
     *
     * @param mode 关闭发生方式
     */
    void close(@NotNull CloseMode mode);

    /**
     * 玩家实体调度器已 retired 时只释放不需要访问玩家状态的资源.
     */
    void retire();

    /**
     * 以插件主动关闭方式释放菜单.
     */
    @Override
    default void close() {
        this.close(CloseMode.PLUGIN);
    }
}
