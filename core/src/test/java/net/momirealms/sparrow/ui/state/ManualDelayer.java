package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;

public final class ManualDelayer implements Delayer {

    private final List<Task> tasks = new ArrayList<>();
    private long now;
    private boolean ignoringCancel;
    private RuntimeException nextScheduleFailure;

    @Override
    @NotNull
    public synchronized Handle schedule(@NotNull Runnable task, long delay) {
        if (delay <= 0) {
            throw new IllegalArgumentException("delay must be positive: " + delay);
        }
        if (this.nextScheduleFailure != null) {
            RuntimeException failure = this.nextScheduleFailure;
            this.nextScheduleFailure = null;
            throw failure;
        }
        Task scheduled = new Task(this.now + delay, task);
        this.tasks.add(scheduled);
        return () -> this.cancel(scheduled);
    }

    private synchronized void cancel(Task task) {
        if (!this.ignoringCancel) {
            this.tasks.remove(task);
        }
    }

    public synchronized void ignoreCancel() {
        this.ignoringCancel = true;
    }

    public synchronized void failNextSchedule(@NotNull RuntimeException failure) {
        this.nextScheduleFailure = failure;
    }

    public void advance(long units) {
        for (long index = 0; index < units; index++) {
            List<Task> due = new ArrayList<>();
            synchronized (this) {
                this.now++;
                for (int i = 0; i < this.tasks.size(); i++) {
                    Task task = this.tasks.get(i);
                    if (task.due <= this.now) {
                        due.add(task);
                    }
                }
                this.tasks.removeAll(due);
            }
            for (int i = 0; i < due.size(); i++) {
                due.get(i).runnable.run();
            }
        }
    }

    public synchronized int pending() {
        return this.tasks.size();
    }

    public boolean scheduled() {
        return this.pending() > 0;
    }

    public synchronized long now() {
        return this.now;
    }

    private static final class Task {
        private final long due;
        private final Runnable runnable;
        private Task(long due, Runnable runnable) {
            this.due = due;
            this.runnable = runnable;
        }
    }
}
