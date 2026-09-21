package net.momirealms.sparrow.ui.scheduler;

import java.util.logging.Level;
import net.momirealms.sparrow.ui.scheduler.task.AsyncTask;
import net.momirealms.sparrow.ui.scheduler.task.LazyAsyncTask;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.bukkit.plugin.Plugin;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public abstract class AbstractJavaScheduler implements SchedulerAdapter {
    private static final int PARALLELISM = 8;

    private final Plugin plugin;
    private final ScheduledThreadPoolExecutor scheduler;
    private final ForkJoinPool worker;

    public AbstractJavaScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = new ScheduledThreadPoolExecutor(4, r -> {
            Thread thread = Executors.defaultThreadFactory().newThread(r);
            thread.setName("sparrow-ui-scheduler");
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.worker = new ForkJoinPool(PARALLELISM, new WorkerThreadFactory(), new ExceptionHandler(), false);
    }

    @Override
    public Executor async() {
        return this.worker;
    }

    @Override
    public SchedulerTask asyncLater(Runnable task, long delay, TimeUnit unit) {
        ScheduledFuture<?> future = this.scheduler.schedule(() -> this.worker.execute(task), delay, unit);
        return new AsyncTask(future);
    }

    @Override
    public SchedulerTask asyncRepeating(Runnable task, long delay, long interval, TimeUnit unit) {
        ScheduledFuture<?> future = this.scheduler.scheduleAtFixedRate(() -> this.worker.execute(task), delay, interval, unit);
        return new AsyncTask(future);
    }

    @Override
    public SchedulerTask asyncRepeating(Consumer<SchedulerTask> task, long delay, long interval, TimeUnit unit) {
        LazyAsyncTask asyncTask = new LazyAsyncTask();
        asyncTask.future = this.scheduler.scheduleAtFixedRate(() -> this.worker.execute(() -> task.accept(asyncTask)), delay, interval, unit);
        return asyncTask;
    }

    @Override
    public void shutdownScheduler() {
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(1, TimeUnit.MINUTES)) {
                this.plugin.getLogger().severe("Timed out waiting for the Sparrow UI scheduler to terminate");
                reportRunningTasks(thread -> thread.getName().equals("sparrow-ui-scheduler"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void shutdownExecutor() {
        this.worker.shutdown();
        try {
            if (!this.worker.awaitTermination(1, TimeUnit.MINUTES)) {
                this.plugin.getLogger().severe("Timed out waiting for the Sparrow UI worker thread pool to terminate");
                reportRunningTasks(thread -> thread.getName().startsWith("sparrow-ui-worker-"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reportRunningTasks(Predicate<Thread> predicate) {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (predicate.test(thread)) {
                this.plugin.getLogger().warning("Thread " + thread.getName() + " is blocked, and may be the reason for the slow shutdown!\n" +
                        Arrays.stream(stack).map(el -> "  " + el).collect(Collectors.joining("\n"))
                );
            }
        });
    }

    private static final class WorkerThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {
        private static final AtomicInteger COUNT = new AtomicInteger(0);

        @Override
        public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setDaemon(true);
            thread.setName("sparrow-ui-worker-" + COUNT.getAndIncrement());
            return thread;
        }
    }

    private final class ExceptionHandler implements UncaughtExceptionHandler {

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            AbstractJavaScheduler.this.plugin.getLogger().log(Level.WARNING, "Thread " + t.getName() + " threw an uncaught exception", e);
        }
    }
}
