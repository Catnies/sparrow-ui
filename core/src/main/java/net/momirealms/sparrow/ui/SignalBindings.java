package net.momirealms.sparrow.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SignalBindings {
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * 收下一条订阅, 并顺手清掉已经关闭的.
     *
     * @param subscription 订阅凭证, 由本集合强持有
     * @return 交给外部的控制句柄, 只能用来解绑或查状态, 不会让持有它的一方钉住宿主
     */
    @NotNull
    public Subscription add(@NotNull Subscription subscription) {
        this.subscriptions.removeIf(Subscription::isClosed);
        this.subscriptions.add(subscription);
        return new WeakHandle(new WeakReference<>(subscription));
    }

    /**
     * 交给外部的非持有型控制句柄.
     * <p>{@code bind} 的返回值经常被存起来留着以后解绑, 若它强持有真正的凭证, 就会顺着凭证
     * 一路钉住回调和宿主, 宿主再也回收不掉. 因此对外只给弱句柄: 宿主还在就能解绑,
     * 宿主已经走了就什么都不做.
     */
    private record WeakHandle(WeakReference<Subscription> target) implements Subscription {

        @Override
        public boolean isClosed() {
            @Nullable Subscription binding = this.target.get();
            return binding == null || binding.isClosed();
        }

        @Override
        public void close() {
            @Nullable Subscription binding = this.target.get();
            if (binding != null) {
                binding.close();
            }
        }
    }
}
