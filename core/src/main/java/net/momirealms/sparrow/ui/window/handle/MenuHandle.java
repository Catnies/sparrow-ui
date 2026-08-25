package net.momirealms.sparrow.ui.window.handle;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.List;

/**
 * 一个 Window 会话对应的协议菜单与客户端已知状态.
 * <p>服务端渲染结果通过此边界打开菜单, 纠正客户端预测并驱动 Bukkit 事件视图.
 */
@ApiStatus.Internal
public interface MenuHandle extends AutoCloseable {

    /**
     * 本轮同步使用的菜单实际光标和客户端显示光标.
     *
     * @param actual 服务端菜单实际持有的光标物品副本
     * @param visual 只供客户端显示的光标物品副本
     */
    record CursorSnapshot(@NotNull ItemStack actual, @NotNull ItemStack visual) {
    }

    /**
     * 准备接管玩家当前菜单的实际光标.
     * <p>替换 Window 时从旧代理菜单接管, 其他情况先关闭当前原版菜单.
     * <strong>打开失败或提前关闭时必须把已接管的光标归还来源菜单.</strong>
     *
     * @param replacingWindow 是否正在替换同一玩家的 Window
     */
    void prepareOpen(boolean replacingWindow);

    /**
     * 打开菜单并发送初始完整状态.
     * <p><strong>slots 及其中的物品只在调用期间有效, 实现不得修改或保留.</strong>
     * 数据包异步发送前还需复制其中的物品.
     *
     * @param title 初始标题
     * @param slots 按协议槽位(raw slot)排列的服务端槽位渲染结果
     * @param cursor 本次同步要用的菜单实际光标与客户端显示光标
     */
    void open(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
     * 将本轮服务端渲染结果和菜单状态同步给客户端.
     * <p>增量同步检查 dirty 槽位与客户端预测, {@code forceFull} 则发送完整状态.
     * <p><strong>slots, dirtySlots 及其中的物品只在调用期间有效, 实现不得修改或保留.</strong>
     *
     * @param slots 按协议槽位(raw slot)排列的服务端槽位渲染结果
     * @param dirtySlots 这一轮可能变过的槽位
     * @param cursor 本次同步要用的菜单实际光标与客户端显示光标
     * @param cursorDirty 这一轮是否需要核对光标
     * @param forceFull 是否强制发送完整状态
     */
    void synchronize(
            ItemStack @NotNull [] slots,
            @NotNull BitSet dirtySlots,
            @NotNull CursorSnapshot cursor,
            boolean cursorDirty,
            boolean forceFull
    );

    /**
     * 通过重新打开界面并附带完整状态来更新标题.
     *
     * @param title 新标题
     * @param slots 按协议槽位(raw slot)排列的服务端槽位渲染结果
     * @param cursor 本次同步要用的菜单实际光标与客户端显示光标
     */
    void reopenWithTitle(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
     * 发送用于确认客户端已处理某项 Window 状态的协议 Ping.
     *
     * @param id Ping 标识
     */
    void sendPing(int id);

    /**
     * 按指定原因关闭菜单并释放会话资源.
     *
     * @param reason 关闭原因
     */
    void close(@NotNull InventoryCloseEvent.Reason reason);

    /**
     * 以"插件主动关闭"的原因关闭菜单.
     */
    @Override
    default void close() {
        this.close(InventoryCloseEvent.Reason.PLUGIN);
    }

    /**
     * 在玩家实体调度器注销后释放不依赖玩家状态的资源.
     * <p><strong>实现不得再读取或修改玩家状态.</strong>
     */
    void retire();

    /**
     * 检查交互是否属于当前会话, 并吸收其中的客户端预测.
     *
     * @param interaction 待检查的交互
     * @return 交互属于当前容器时返回 {@code true}
     */
    boolean accepts(@NotNull MenuInput.Common.Interaction interaction);

    /**
     * 按接收顺序从缓冲区取出最多 limit 条入站消息.
     *
     * @param limit 本次最多取出的输入数量
     * @return 取出的不可变输入列表
     */
    @NotNull
    List<MenuInput> drainInputs(int limit);

    /**
     * 返回入站缓冲区是否曾溢出, Window 会据此关闭无法继续安全同步的会话.
     *
     * @return 曾超过容量阈值时返回 {@code true}
     */
    boolean hasInputOverflowed();

    /**
     * 返回与服务端渲染结果隔离的 Bukkit 事件 InventoryView.
     * <p>{@link InventoryView#getItem(int)} 与 {@link InventoryView#getCursor()} 返回独立副本.
     *
     * @return InventoryView
     */
    @NotNull
    InventoryView view();

    /**
     * 用当前服务端状态重置下一次 Bukkit 事件读取的副本.
     * <p>本 tick 已积累的触碰标记继续保留, 供最终同步纠正客户端.
     *
     * @param slots 按协议槽位排列的当前服务端渲染结果
     * @param renderedSlots 本次事件前刚重新渲染的槽位
     * @param cursor 当前菜单实际光标
     */
    void resetBukkitEventView(ItemStack @NotNull [] slots, @NotNull BitSet renderedSlots, @NotNull ItemStack cursor);

    /**
     * 取出最近一次 Bukkit 事件写入的光标并清空该事件记录.
     * <p>调用必须紧跟事件返回, 下一次 {@link #resetBukkitEventView} 会覆盖事件副本.
     *
     * @return 最近一次事件写过光标时返回写入值, 没写过时返回 {@code null}
     */
    @Nullable
    ItemStack takeBukkitEventCursor();

    /**
     * 转移最近一次 Bukkit 事件写入的槽位并清空该事件记录.
     * <p>写入内容仍从 {@link #view()} 读取, 本 tick 的累积触碰位图保持不变.
     *
     * @param destination 接收本次事件写入槽位的可变位图
     */
    void drainBukkitEventSlots(@NotNull BitSet destination);

    int containerId();

    int stateId();

    /**
     * 返回菜单实际持有的光标物品副本.
     *
     * @return 调用方可以随意修改的菜单实际光标副本
     */
    @NotNull
    ItemStack cursor();

    /**
     * 返回菜单光标的 NMS 句柄.
     * <p><strong>返回值可能借用菜单底层状态, 调用方不得修改.</strong>
     *
     * @return 菜单实际光标的 NMS 句柄
     */
    @NotNull
    default Object unsafeCursor() {
        return ItemUtils.getItemStackHandle(this.cursor());
    }

    /**
     * 使用输入副本覆盖菜单实际光标.
     * <p><strong>调用方负责标脏并调用 synchronize 完成客户端同步.</strong>
     *
     * @param cursor 新的菜单实际光标, 空物品表示清空
     */
    void cursor(@NotNull ItemStack cursor);
}
