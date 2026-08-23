package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 一名玩家从进入到离开一整段多 Window 交互的会话.
 * <p>会话随根窗的打开而诞生. {@link Window#open()} 直接打开的窗成为新根窗并创建会话,
 * {@link Window#navigate} 打开的窗归属上一扇所在的会话.
 * <p>会话内跳转与返回不触发会话结束处理器, 只有整段交互结束时会话结束处理器才恰好触发一次.
 */
public interface WindowSession {

    /**
     * 会话的类型, 取自根窗 Builder 的 {@link Window.Builder#setSessionKind} 声明.
     *
     * @return 会话类型
     */
    @NotNull
    Kind kind();

    /**
     * 结束会话, 关闭当前窗, 以 {@link InventoryCloseEvent.Reason#PLUGIN} 触发结束处理器并清空全部成员.
     * 重复调用无事发生.
     *
     * @return 结束请求的执行结果
     */
    @NotNull
    CompletableFuture<EndResult> end();

    /**
     * 绑定到指定的 Signal, Signal 将会持有本会话的弱引用.
     * <p>绑定随会话结束一并摘掉, 之后不再回调.
     * <p>绑定不补发当前值, 第一次回调发生在下一次标脏.
     *
     * @param signal 数据源
     * @param callback 失效回调
     * @return 订阅凭证, 可用于提前解绑
     */
    @NotNull
    Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super WindowSession> callback);

    /**
     * 替换会话结束时依次执行的处理器列表.
     *
     * @param sessionEndHandlers 新处理器列表
     */
    void setSessionEndHandlers(@NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> sessionEndHandlers);

    /**
     * 当前结束处理器列表的快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<InventoryCloseEvent.Reason>> getSessionEndHandlers();

    /**
     * 在现有结束处理器末尾追加一个处理器, 它在整段交互结束时恰好触发一次, 会话内跳转与返回绝不触发.
     * <p>参数沿用 Bukkit 关闭原因. PLAYER 表示玩家主动离开, DISCONNECT 表示断线,
     * PLUGIN 表示插件结束(含 {@link #end()}), OPEN_NEW 表示会话外 Window 顶替.
     *
     * @param sessionEndHandler 结束处理器
     */
    void addSessionEndHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> sessionEndHandler);

    /**
     * 移除一个与给定对象相等的结束处理器.
     *
     * @param sessionEndHandler 要移除的结束处理器
     */
    void removeSessionEndHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> sessionEndHandler);

    /**
     * 此会话的所属玩家.
     *
     * @return 所属玩家
     */
    @NotNull
    Player viewer();

    /**
     * 当前 Window, 会话已结束时为 null.
     *
     * @return 当前 Window, 会话已结束时为 null
     */
    @Nullable
    Window current();

    /**
     * 当前路径快照, 从根窗到当前窗.
     * <p>STACK 与 RETAINED_STACK 是活动栈, 不含已弹出或保留区中的窗.
     * TREE 是根到当前位置的路径(不含其他枝上的成员). 同一实例在环形栈中可出现多次.
     *
     * @return 不可变的 Window 列表
     */
    @Unmodifiable
    @NotNull List<Window> chain();

    /**
     * 当前位置上面是否还有上一扇可回, 即 {@link Window#back()} 会不会发生返回.
     *
     * @return 有上一扇可回时返回 true
     */
    boolean hasBack();

    /**
     * 会话是否尚未结束.
     *
     * @return 尚未结束时返回 true
     */
    boolean active();

    /**
     * 结束请求的执行结果.
     */
    enum EndResult {
        ENDED,          // 已结束会话
        ALREADY_ENDED   // 会话此前已经结束
    }

    /**
     * 会话的类型, 由根窗 Builder 的 {@link Window.Builder#setSessionKind} 声明, 决定成员结构与离场处置.
     */
    enum Kind {
        /**
         * 线性栈, 默认型. {@link Window#navigate} 不查重, 同一 Window 实例可以压入多次.
         * {@link Window#back()} 弹出栈顶, 栈中不再出现该实例时会话丢弃对它的引用.
         * 适合每次进入都应当是全新状态的菜单.
         */
        STACK,
        /**
         * 保留栈. 结构与 {@link #STACK} 完全一致, 唯一差异是被弹出的 Window 不丢引用,
         * 而是保留到会话结束才随会话一起释放.
         */
        RETAINED_STACK,
        /**
         * 树. {@link Window#navigate} 查重, 目标已在树中时当前位置直接移过去并以原实例重新打开,
         * 不在时成为当前节点的新孩子. {@link Window#back()} 回到父节点且不丢弃任何成员.
         * 步入过的 Window 全部保留到会话结束.
         */
        TREE
    }
}
