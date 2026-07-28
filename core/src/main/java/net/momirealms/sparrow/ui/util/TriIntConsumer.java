package net.momirealms.sparrow.ui.util;

import org.jspecify.annotations.NullUnmarked;

@NullUnmarked
@FunctionalInterface
public interface TriIntConsumer {

    void accept(int a, int b, int c);

    default TriIntConsumer andThen(TriIntConsumer after) {
        return (a, b, c) -> {
            accept(a, b, c);
            after.accept(a, b, c);
        };
    }

}
