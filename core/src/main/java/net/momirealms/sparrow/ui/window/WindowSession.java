package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 一名玩家从进来到离开的一整段多 Window 交互.
 * <p>会话跟着根窗一起出现: {@link Window#open()} 打开的窗成为新根窗, 会话就此建立;
 * {@link Window#navigate} 打开的窗归属上一扇所在的会话.
 * <p>会话里来回跳转和返回都不触发结束处理器, 只有整段交互真的结束, 它才恰好跑一次.
 */
public interface WindowSession {

    /**
     * 这条会话是哪种结构, 由根窗 Builder 的 {@link Window.Builder#setSessionKind} 声明.
     *
     * @return 会话类型
     */
    @NotNull
    Kind kind();

    /**
     * 结束整段会话: 关掉当前窗, 以 {@link WindowCloseReason#PLUGIN} 触发结束处理器, 然后清空所有成员.
     * <p>重复调用不会有第二次效果.
     *
     * @return 结束请求的执行结果
     */
    @NotNull
    CompletableFuture<EndResult> end();

    /**
     * 让 Signal 每次失效都回调一次, Signal 只弱持有本会话.
     * <p>绑定随会话结束一起摘掉, 之后不再回调; 也不会补一趟当前值, 第一次回调要等下一次标脏.
     *
     * @param signal 数据源
     * @param callback 失效回调
     * @return 订阅凭证, 可用于提前解绑
     */
    @NotNull
    Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super WindowSession> callback);

    /**
     * 整批换掉会话结束时要跑的处理器.
     *
     * @param sessionEndHandlers 新处理器列表
     */
    void setSessionEndHandlers(@NotNull List<? extends Consumer<? super WindowCloseReason>> sessionEndHandlers);

    /**
     * 现在的结束处理器, 给一份快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<WindowCloseReason>> getSessionEndHandlers();

    /**
     * 在结束处理器末尾追加一个, 它在整段交互结束时恰好跑一次; 会话里跳转和返回都不会触发它.
     * <p>它收到的原因里, PLAYER 是玩家主动离开, DISCONNECT 是断线, PLUGIN 是插件结束会话(含 {@link #end()}),
     * OPEN_NEW 是会话外的 Window 顶替了当前这个.
     *
     * @param sessionEndHandler 结束处理器
     */
    void addSessionEndHandler(@NotNull Consumer<? super WindowCloseReason> sessionEndHandler);

    /**
     * 按 equals 摘掉一个结束处理器.
     *
     * @param sessionEndHandler 要移除的结束处理器
     */
    void removeSessionEndHandler(@NotNull Consumer<? super WindowCloseReason> sessionEndHandler);

    /**
     * 这场交互的玩家.
     *
     * @return 所属玩家
     */
    @NotNull
    Player viewer();

    /**
     * 现在停在哪一扇 Window.
     *
     * @return 当前 Window; 会话已经结束时为 null
     */
    @Nullable
    Window current();

    /**
     * 当前路径的快照, 从根窗排到当前窗.
     * <p>STACK 和 RETAINED_STACK 给的是活动栈, 已经弹出或进了保留区的窗不在里面.
     * TREE 给的是根到当前位置那一条, 其他枝上的成员不算; 同一个实例在环形栈里可以出现多次.
     *
     * @return 不可变的 Window 列表
     */
    @Unmodifiable
    @NotNull List<Window> chain();

    /**
     * 当前位置上面还有没有上一扇, 也就是 {@link Window#back()} 会不会真的返回.
     *
     * @return 有上一扇时为 true
     */
    boolean hasBack();

    /**
     * 这场会话还没结束.
     *
     * @return 还没结束时为 true
     */
    boolean active();

    /**
     * 这次 end() 请求的结果.
     */
    enum EndResult {
        ENDED,          // 这次把会话结束了
        ALREADY_ENDED   // 会话之前就已经结束
    }

    /**
     * 这条会话用哪种结构: 由根窗 Builder 的 {@link Window.Builder#setSessionKind} 声明, 成员怎么留怎么丢都看它.
     */
    enum Kind {
        /**
         * 线性栈, 默认就是这个. {@link Window#navigate} 不查重, 同一个 Window 实例能压进去好几次;
         * {@link Window#back()} 弹栈顶, 某个实例弹出后栈里再没有它, 会话就不再持有它.
         * 适合每次进去都该是全新状态的菜单.
         */
        STACK,
        /**
         * 保留栈: 结构和 {@link #STACK} 一模一样, 差别只在弹出去的 Window 不丢引用,
         * 一直留到会话结束才跟着释放.
         */
        RETAINED_STACK,
        /**
         * 树: {@link Window#navigate} 会查重, 目标已经在树里就把当前位置挪过去, 用原来那个实例重新打开;
         * 不在就挂成当前节点的新孩子. {@link Window#back()} 回父节点, 谁都不丢.
         * 走进去过的 Window 一律留到会话结束.
         */
        TREE
    }
}
