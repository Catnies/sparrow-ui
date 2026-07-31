package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class MiscUtils {
    private MiscUtils() {}

    /**
     * 将接受 {@code ? super T} 的消费者窄化为 {@code Consumer<T>}.
     * 该消费者本就接受 T 或 T 的父类实例, 传入 T 必然安全, 因此此处的强制转换不会导致类型错误.
     *
     * @param consumer 待窄化的消费者
     * @param <T> 消费者接受的参数类型
     * @return 窄化后的消费者, 与原消费者为同一实例
     */
    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> narrowConsumer(Consumer<? super T> consumer) {
        return (Consumer<T>) consumer;
    }

    /**
     * 返回在末尾追加指定元素后的新列表, 原列表不会被修改.
     *
     * @param values 原列表
     * @param value 待追加的元素
     * @param <T> 列表元素类型
     * @return 包含原列表全部元素及新元素的不可变列表
     */
    public static <T> List<T> append(List<T> values, T value) {
        ArrayList<T> copy = new ArrayList<>(values.size() + 1);
        copy.addAll(values);
        copy.add(value);
        return List.copyOf(copy);
    }

    /**
     * 返回移除首个匹配元素后的新列表, 原列表不会被修改.
     *
     * @param values 原列表
     * @param value 待移除的元素
     * @param <T> 列表元素类型
     * @return 移除元素后的不可变列表
     */
    public static <T> List<T> remove(List<T> values, T value) {
        ArrayList<T> copy = new ArrayList<>(values);
        copy.remove(value);
        return List.copyOf(copy);
    }

    /**
     * 将通配符类型的消费者列表复制为不可变的 {@code Consumer<T>} 列表.
     *
     * @param consumers 原消费者列表
     * @param <T> 消费者接受的参数类型
     * @return 窄化后的不可变消费者列表
     */
    public static <T> List<Consumer<T>> copyConsumers(List<? extends Consumer<? super T>> consumers) {
        ArrayList<Consumer<T>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }

    /**
     * 将接受 {@code ? super T} 与 {@code ? super U} 的双参数消费者窄化为 {@code BiConsumer<T, U>}.
     * 与 {@link #narrowConsumer(Consumer)} 同理, 此处的强制转换是安全的.
     *
     * @param consumer 待窄化的双参数消费者
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     * @return 窄化后的双参数消费者, 与原消费者为同一实例
     */
    @SuppressWarnings("unchecked")
    public static <T, U> BiConsumer<T, U> narrowBiConsumer(BiConsumer<? super T, ? super U> consumer) {
        return (BiConsumer<T, U>) consumer;
    }

    /**
     * 将通配符类型的双参数消费者列表复制为不可变的 {@code BiConsumer<T, U>} 列表.
     *
     * @param consumers 原双参数消费者列表
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     * @return 窄化后的不可变双参数消费者列表
     */
    public static <T, U> List<BiConsumer<T, U>> copyBiConsumers(List<? extends BiConsumer<? super T, ? super U>> consumers) {
        ArrayList<BiConsumer<T, U>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowBiConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }

    /**
     * 按数组元素是否非 null 构建位图, 每个字节从低位到高位对应连续八个元素.
     *
     * @param values 待检查的数组
     * @param <T> 元素类型
     * @return 长度为 {@code ceil(values.length / 8)} 的位图
     */
    public static <T> byte @NotNull [] buildMask(@Nullable T @NotNull [] values) {
        byte[] mask = new byte[(values.length + 7) / 8];
        for (int index = 0; index < values.length; index++) {
            if (values[index] != null) {
                mask[index >> 3] |= (byte) (1 << (index & 7));
            }
        }
        return mask;
    }
}
