package net.momirealms.sparrow.ui;

/**
 * 类型化更新事件的来源.
 *
 * @param <T> 更新类型
 */
public interface Observable<T> {

    /**
     * 为此来源订阅观察者, 每次调用都会创建独立的订阅关系, 即使同一观察者被重复提供也是如此.
     * <p><strong>订阅由来源保活</strong>, 订阅一直持续到显式关闭或来源本身被回收.
     *
     * @param observer 要通知的观察者
     * @return 订阅凭证, 用于显式退订; 丢弃它不影响订阅
     */
    Subscription subscribe(Observer<? super T> observer);
}
