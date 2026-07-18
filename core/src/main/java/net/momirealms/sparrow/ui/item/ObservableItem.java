package net.momirealms.sparrow.ui.item;

/**
 * 允许调用方通知当前显示结果已经失效的 Item.
 *
 * <p>观察关系由 {@link Item#attach(net.momirealms.sparrow.ui.Observer)} 管理,
 * 此 Interface 只暴露主动通知能力.</p>
 */
public interface ObservableItem extends Item {

    /**
     * 通知所有当前挂载此 Item 的最终窗口槽位重新渲染.
     * 此方法可以跨线程调用；Window 只能合并脏槽，不能在调用线程直接发包.
     */
    void notifyWindows();
}
