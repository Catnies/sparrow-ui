package net.momirealms.sparrow.ui;

/**
 * 类型化更新事件的来源.
 *
 * @param <T> 更新类型
 */
public interface Observable<T> {

    /**
     * 为此来源订阅观察者, 每次调用都会创建独立的订阅关系, 即使同一观察者被重复提供也是如此.
     * 返回的订阅凭证拥有该订阅关系.
     *
     * @param observer 要通知的观察者
     * @return 订阅凭证
     */
    Subscription subscribe(Observer<? super T> observer);
}
