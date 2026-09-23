package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 支持集合修改、元素钩子和批量通知的 {@link SetSignal}. 线程安全由被包装的集合决定.
 *
 * @param <E> 元素类型
 */
@ApiStatus.NonExtendable
public interface MutableSetSignal<E> extends SetSignal<E> {

    /**
     * 挂一个元素钩子, 元素存入<strong>之前</strong>调用, 返回值才是真正存进去的. 生命周期与顺序见 {@link MutableListSignal#beforeAdd}.
     * <p>{@code add} 先用原元素查重, 已有就不跑钩子. 钩子换出的元素若与已有元素判等, 这次放入会落空.
     * <p><strong>只弱持有钩子, 调用方必须保存返回的凭证</strong>, 寿命见 {@link MutableListSignal#beforeAdd}.
     *
     * @param hook 收到调用方要放的元素, 返回真正存进去的
     * @return 钩子凭证, 关闭即摘除这个钩子
     */
    @NotNull
    Subscription beforeAdd(@NotNull Function<? super E, ? extends E> hook);

    /**
     * 挂一个元素钩子, 元素移除<strong>之后</strong>调用. 异常与通知语义见 {@link MutableListSignal#afterRemove}.
     * <p>寿命同 {@link #beforeAdd}: 只弱持有钩子, 调用方必须保存凭证.
     *
     * @param hook 收到被移除的元素
     * @return 钩子凭证, 关闭即摘除这个钩子
     */
    @NotNull
    Subscription afterRemove(@NotNull Consumer<? super E> hook);

    /**
     * 把 {@code changes} 期间本线程对本集合的变更合并成一次通知, 语义见 {@link MutableListSignal#batch}.
     *
     * @param changes 要合并的一批变更
     */
    void batch(@NotNull Runnable changes);

}
