package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

/**
 * {@link KeyedSignal} 的失效侧, <strong>弱持有</strong>目标. 四个方法与 {@code KeyedSignal} 上同名的那四个行为一致, 目标已被回收时什么也不做.
 * <p>signal 与登记表同寿命时(插件级单例)不需要它, 直接调 {@code signal.dirty(key)} 即可. 它只在登记表比 signal 活得久时才有意义:
 * 菜单级、会话级、每副本一个的 signal 要登记到全局事件总线、Redis 订阅、定时任务上, 写成 {@code bus.on(X, e -> signal.remove(...))}
 * 会把 signal 强捕获进一个永不消失的登记表, 为了防泄漏加的功能自己变成泄漏源. 换成 {@code WeakKeyedControl<K> control = signal.weakControl()}
 * 再捕获 {@code control}, signal 死后登记只剩一个几十字节的空壳.
 * <p>与自己手写 {@code WeakReference} 的区别: 空值检查忘不掉; {@link #isStale} 让讲究的登记表能把自己的条目摘掉;
 * 以及弱持的是<strong>正确的对象</strong>. {@code PlayerKeyedSignal} 的 {@code at(uuid)} 句柄强持的是它里面的委托而不是包装器,
 * 用户只留句柄时包装器会被回收, 自己弱持包装器的人会静默失效, 这里弱持的是委托.
 * <p>线程安全同 {@code KeyedSignal}. <strong>不要在同一个 KeyedSignal 的回调里调 {@link #remove} / {@link #clear}</strong>, 那会在该 key 的重算里重入.
 *
 * @param <K> 分区 key 类型
 */
public sealed interface WeakKeyedControl<K> permits AbstractKeyedSignal.Control {

    /**
     * 同 {@link KeyedSignal#dirty}.
     *
     * @param key 分区 key
     */
    void dirty(@NotNull K key);

    /**
     * 同 {@link KeyedSignal#dirtyAll}.
     */
    void dirtyAll();

    /**
     * 同 {@link KeyedSignal#remove}.
     *
     * @param key 分区 key
     */
    void remove(@NotNull K key);

    /**
     * 同 {@link KeyedSignal#clear}.
     */
    void clear();

    /**
     * 目标 signal 是否已被回收. 为真之后其余方法都是空操作, 登记表可以据此把自己的条目摘掉.
     *
     * @return 目标已被回收
     */
    boolean isStale();
}
