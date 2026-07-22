package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;
import java.util.List;

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
     * 此会话的 Minecraft 容器编号.
     */
    int containerId();

    /**
     * 返回供 Bukkit 事件读取的协议视图.
     */
    @NotNull
    InventoryView view();

    /**
     * 返回 Paper 玩家物品栏的变更版本.
     *
     * <p>Window 使用此版本门控底部物品栏扫描. Adapter 无法提供精确版本时可以返回一个
     * 持续变化的值, 以退化为每 tick 扫描.</p>
     *
     * @return 当前玩家物品栏版本
     */
    int playerInventoryVersion();

    /**
     * 客户端当前应回传的容器 state id.
     *
     * @return 当前协议状态编号
     */
    int stateId();

    /**
     * 校验交互所属会话和 state id, 并吸收其中非权威的客户端预测.
     *
     * @param interaction 待校验的交互
     * @return 交互属于当前协议状态时返回 {@code true}
     */
    boolean accepts(@NotNull MenuInput.Common.Interaction interaction);

    /**
     * 返回入站消息缓冲区是否已经溢出.
     *
     * <p>缓冲、代际筛选和 Netty 线程交接均由菜单 Adapter 管理, Window 只消费当前会话的领域输入.</p>
     *
     * @return 入站消息是否曾超过 Adapter 容量
     */
    boolean hasInputOverflowed();

    /**
     * 按接收顺序处理至多指定数量的当前会话输入.
     *
     * @param limit 本次最多移除的输入数量
     * @return 不可变的领域输入列表
     */
    @NotNull
    List<MenuInput> drainInputs(int limit);

    /**
     * 打开菜单并发送初始完整状态.
     *
     * <p>实现必须在方法返回前读取槽位数组, 不得保留调用方的可变数组引用.</p>
     *
     * @param title 初始标题
     * @param slots 按原始槽位编号排列的权威物品
     * @param cursor 权威光标物品
     */
    void open(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull ItemStack cursor);

    /**
     * 将远端容器镜像同步到当前服务端权威状态.
     *
     * <p>实现检查 dirty 槽位和此前收到的客户端预测. {@code forceFull} 为真时忽略增量候选并
     * 发送完整状态. 参数只在调用期间有效, 实现不得修改或保留数组、位图引用.</p>
     *
     * @param slots 按原始槽位编号排列的权威物品
     * @param dirtySlots 本轮可能变化的槽位
     * @param cursor 权威光标物品
     * @param cursorDirty 是否需要核对光标
     * @param forceFull 是否强制发送完整状态
     */
    void synchronize(
            ItemStack @NotNull [] slots,
            @NotNull BitSet dirtySlots,
            @NotNull ItemStack cursor,
            boolean cursorDirty,
            boolean forceFull
    );

    /**
     * 用重新打开界面和完整状态更新客户端标题.
     *
     * @param title 新标题
     * @param slots 按原始槽位编号排列的权威物品
     * @param cursor 权威光标物品
     */
    void updateTitle(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull ItemStack cursor);

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
