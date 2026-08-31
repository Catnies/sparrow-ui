package net.momirealms.sparrow.ui.scheduler;

import net.momirealms.sparrow.ui.scheduler.task.AsyncTask;
import net.momirealms.sparrow.ui.scheduler.task.LazyAsyncTask;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * 使用独立定时线程池和工作线程池实现墙钟异步调度.
 *
 * @param <T> 世界类型
 */
public abstract class AbstractJavaScheduler<T> implements SchedulerAdapter<T> {
    private static final int PARALLELISM = 16;

    private final Plugin plugin;
    private final ScheduledThreadPoolExecutor scheduler;
    private final ForkJoinPool worker;

    /**
     * 创建由指定插件拥有的异步调度器.
     *
     * @param plugin 任务所属插件
     */
    protected AbstractJavaScheduler(@NotNull Plugin plugin) {
        this.plugin = plugin;
        AtomicInteger schedulerThreads = new AtomicInteger();
        this.scheduler = new ScheduledThreadPoolExecutor(4, runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName("sparrow-ui-scheduler-" + schedulerThreads.getAndIncrement());
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.worker = new ForkJoinPool(PARALLELISM, new WorkerThreadFactory(), new ExceptionHandler(), false);
    }

    @Override
    @NotNull
    public Executor async() {
        return this.worker;
    }

    @Override
    @NotNull
    public SchedulerTask asyncLater(@NotNull Runnable task, long delay, @NotNull TimeUnit unit) {
        ScheduledFuture<?> future = this.scheduler.schedule(() -> this.worker.execute(task), delay, unit);
        return new AsyncTask(future);
    }

    @Override
    @NotNull
    public SchedulerTask asyncRepeating(@NotNull Runnable task, long delay, long interval, @NotNull TimeUnit unit) {
        ScheduledFuture<?> future = this.scheduler.scheduleAtFixedRate(() -> this.worker.execute(task), delay, interval, unit);
        return new AsyncTask(future);
    }

    @Override
    @NotNull
    public SchedulerTask asyncRepeating(@NotNull Consumer<SchedulerTask> task, long delay, long interval, @NotNull TimeUnit unit) {
        LazyAsyncTask asyncTask = new LazyAsyncTask();
        asyncTask.future(this.scheduler.scheduleAtFixedRate(
                () -> this.worker.execute(() -> task.accept(asyncTask)),
                delay,
                interval,
                unit
        ));
        return asyncTask;
    }

    @Override
    public void shutdownScheduler() {
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(1, TimeUnit.MINUTES)) {
                this.plugin.getLogger().severe("Timed out waiting for the Sparrow UI scheduler to terminate");
                this.reportRunningTasks(thread -> thread.getName().startsWith("sparrow-ui-scheduler-"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void shutdownExecutor() {
        this.worker.shutdown();
        try {
            if (!this.worker.awaitTermination(1, TimeUnit.MINUTES)) {
                this.plugin.getLogger().severe("Timed out waiting for the Sparrow UI worker pool to terminate");
                this.reportRunningTasks(thread -> thread.getName().startsWith("sparrow-ui-worker-"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void reportRunningTasks(Predicate<Thread> predicate) {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (predicate.test(thread)) {
                this.plugin.getLogger().warning("Thread " + thread.getName() + " is still running:\n"
                        + Arrays.stream(stack).map(element -> "  " + element).collect(Collectors.joining("\n")));
            }
        });
    }

    private static final class WorkerThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {
        private static final AtomicInteger COUNT = new AtomicInteger();

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
        public void uncaughtException(Thread thread, Throwable throwable) {
            AbstractJavaScheduler.this.plugin.getLogger().log(
                    Level.WARNING,
                    "Thread " + thread.getName() + " threw an uncaught exception",
                    throwable
            );
        }
    }
}
