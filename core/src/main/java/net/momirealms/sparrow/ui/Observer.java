package net.momirealms.sparrow.ui;

/**
 * 接收来自 {@link Observable} 的类型化更新.
 *
 * @param <T> 更新类型
 */
@FunctionalInterface
public interface Observer<T> {

    void onUpdate(T update);
}
