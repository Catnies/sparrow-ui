package net.momirealms.sparrow.ui;

/**
 * 强订阅的回调, 由被订阅方持有, 接收类型化的更新.
 * <strong>订阅由被订阅方保活</strong>, 一直持续到凭证被显式关闭或被订阅方本身被回收.
 *
 * @param <T> 更新类型
 */
@FunctionalInterface
public interface Observer<T> {

    /**
     * 接收一次类型化更新.
     *
     * @param update 更新内容
     */
    void onUpdate(T update);
}
