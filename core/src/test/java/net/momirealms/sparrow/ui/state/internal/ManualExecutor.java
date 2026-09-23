package net.momirealms.sparrow.ui.state.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

public final class ManualExecutor implements Executor {

    private final Deque<Runnable> queue = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
        this.queue.add(command);
    }

    public int pending() {
        return this.queue.size();
    }

    public int drain() {
        int executed = 0;
        Runnable task;
        while ((task = this.queue.poll()) != null) {
            task.run();
            executed++;
        }
        return executed;
    }
}
