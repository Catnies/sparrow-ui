package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Experimental
public final class NMSPacketEvent {
    private final Object packet;
    private @Nullable Object replacement;
    private boolean cancelled;

    NMSPacketEvent(@NotNull Object packet) {
        this.packet = packet;
    }

    @NotNull
    public Object packet() {
        return this.packet;
    }

    /**
     * 用另一个包顶掉当前这个包.
     * <p>bundle 里的子包也能调用, 但换掉的是整个 bundle: 事件挂在根包上, 子包和根包共用同一个替换出口.
     *
     * @param replacement 顶替的包
     */
    public void replacePacket(@NotNull Object replacement) {
        this.replacement = replacement;
    }

    public boolean usingReplacement() {
        return this.replacement != null;
    }

    @Nullable
    public Object replacement() {
        return this.replacement;
    }

    public boolean cancelled() {
        return this.cancelled;
    }

    /**
     * 取消这个包, 它不会再发出去.
     * <p>出站 bundle 里的子包被取消, 结果就是整个 bundle 都不发.
     *
     * @param cancelled 是否取消
     */
    public void cancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
