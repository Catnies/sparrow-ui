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
     * @return 控制句柄, 只能用来解绑或查状态.
     */
    @NotNull
    public Subscription add(@NotNull Subscription subscription) {
        this.subscriptions.removeIf(Subscription::isClosed);
        this.subscriptions.add(subscription);
        return new WeakHandle(new WeakReference<>(this), new WeakReference<>(subscription));
    }

    private record WeakHandle(WeakReference<SignalBindings> owner, WeakReference<Subscription> target) implements Subscription {

        @Override
        public boolean isClosed() {
            @Nullable Subscription binding = this.target.get();
            return binding == null || binding.isClosed();
        }

        @Override
        public void close() {
            @Nullable Subscription binding = this.target.get();
            if (binding == null) {
                return;
            }
            binding.close();
            // 顺手从持有方摘掉.
            @Nullable SignalBindings bindings = this.owner.get();
            if (bindings != null) {
                bindings.subscriptions.remove(binding);
            }
        }
    }
}
