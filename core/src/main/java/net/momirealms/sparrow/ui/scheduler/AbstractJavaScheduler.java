package net.momirealms.sparrow.ui.scheduler;

import net.momirealms.sparrow.ui.scheduler.task.AsyncTask;
import net.momirealms.sparrow.ui.scheduler.task.LazyAsyncTask;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public abstract class AbstractJavaScheduler<T> implements SchedulerAdapter<T> {
    private static final int PARALLELISM = 16;
    private static final AtomicInteger SCHEDULER_THREAD_COUNT = new AtomicInteger();

    private final Plugin plugin;
    private final ScheduledThreadPoolExecutor scheduler; // 定时调度线程池
    private final ForkJoinPool worker; // 运行线程池

    public AbstractJavaScheduler(Plugin plugin) {
        this.plugin = plugin;
        // 创建一个核心线程数为 4 的 ScheduledThreadPoolExecutor
        this.scheduler = new ScheduledThreadPoolExecutor(4, r -> {
            Thread thread = Executors.defaultThreadFactory().newThread(r);
            thread.setName("sparrow-ui-scheduler-" + SCHEDULER_THREAD_COUNT.getAndIncrement());
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true); // 取消任务时自动移除
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); // 关闭时不执行已存在的延迟任务
        // 创建一个并行度为 PARALLELISM 的 ForkJoinPool 作为异步工作线程池, 使用自定义的线程工厂和异常处理器.
        this.worker = new ForkJoinPool(PARALLELISM, new WorkerThreadFactory(), new ExceptionHandler(), false);
    }

    /**
     * 获取异步执行器.
     * 返回内部的 ForkJoinPool, 提交的任务将在工作线程池中并行执行.
     *
     * @return ForkJoinPool 异步执行器实例.
     */
    @Override
    public Executor async() {
        return this.worker;
    }

    /**
     * 在异步线程池中延迟执行一个任务.
     *
     * @param task  需要执行的任务.
     * @param delay 延迟时间.
     * @param unit  延迟时间的单位.
     * @return 表示该调度任务的 AsyncTask 实例.
     */
    @Override
    public SchedulerTask asyncLater(Runnable task, long delay, TimeUnit unit) {
        ScheduledFuture<?> future = this.scheduler.schedule(() -> this.worker.execute(task), delay, unit);
        return new AsyncTask(future);
    }

    /**
     * 在异步线程池中以固定频率重复执行一个任务.
     *
     * @param task     需要重复执行的任务.
     * @param delay    首次执行前的延迟时间.
     * @param interval 两次执行之间的间隔时间.
     * @param unit     时间单位.
     * @return 表示该调度任务的 AsyncTask 实例.
     */
    @Override
    public SchedulerTask asyncRepeating(Runnable task, long delay, long interval, TimeUnit unit) {
        ScheduledFuture<?> future = this.scheduler.scheduleAtFixedRate(() -> this.worker.execute(task), delay, interval, unit);
        return new AsyncTask(future);
    }

    /**
     * 在异步线程池中以固定频率重复执行一个任务, 任务可以接收 SchedulerTask 参数以实现自我取消.
     *
     * @param task     需要重复执行的任务, 接收自身的 SchedulerTask 作为参数.
     * @param delay    首次执行前的延迟时间.
     * @param interval 两次执行之间的间隔时间.
     * @param unit     时间单位.
     * @return 表示该调度任务的 LazyAsyncTask 实例.
     */
    @Override
    public SchedulerTask asyncRepeating(Consumer<SchedulerTask> task, long delay, long interval, TimeUnit unit) {
        LazyAsyncTask asyncTask = new LazyAsyncTask();
        asyncTask.future = this.scheduler.scheduleAtFixedRate(() -> this.worker.execute(() -> task.accept(asyncTask)), delay, interval, unit);
        return asyncTask;
    }

    /**
     * 关闭调度器.
     */
    @Override
    public void shutdownScheduler() {
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(1, TimeUnit.MINUTES)) {
                this.plugin.getLogger().severe("Timed out waiting for the SparrowUI scheduler to terminate");
                reportRunningTasks(thread -> thread.getName().startsWith("sparrow-ui-scheduler-"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Thread is interrupted: " + e.getMessage());
        }
    }

    /**
     * 关闭异步执行器 (ForkJoinPool).
     */
    @Override
    public void shutdownExecutor() {
        this.worker.shutdown();
        try {
            if (!this.worker.awaitTermination(1, TimeUnit.MINUTES)) {
                this.plugin.getLogger().severe("Timed out waiting for the SparrowUI worker thread pool to terminate");
                reportRunningTasks(thread -> thread.getName().startsWith("sparrow-ui-worker-"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Thread is interrupted: " + e.getMessage());
        }
    }

    /**
     * 报告满足指定条件的仍在运行的线程及其堆栈信息.
     * 用于在关闭超时时诊断阻塞线程.
     *
     * @param predicate 用于筛选目标线程的条件判断器.
     */
    private void reportRunningTasks(Predicate<Thread> predicate) {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (predicate.test(thread)) {
                this.plugin.getLogger().warning("Thread " + thread.getName() + " is blocked, and may be the reason for the slow shutdown!\n" +
                        Arrays.stream(stack).map(el -> "  " + el).collect(Collectors.joining("\n"))
                );
            }
        });
    }

    /**
     * ForkJoinPool 的工作线程工厂.
     * 为工作线程设置守护线程属性和统一的命名规则.
     */
    private static final class WorkerThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {
        private static final AtomicInteger COUNT = new AtomicInteger(0);

        /**
         * 创建一个新的 ForkJoinWorkerThread.
         *
         * @param pool 线程所属的 ForkJoinPool.
         * @return 新创建的 ForkJoinWorkerThread 实例.
         */
        @Override
        public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setDaemon(true);
            thread.setName("sparrow-ui-worker-" + COUNT.getAndIncrement());
            return thread;
        }
    }

    /**
     * 工作线程的未捕获异常处理器.
     * 当工作线程中发生未捕获的异常时, 将异常信息记录到插件日志中.
     */
    private final class ExceptionHandler implements UncaughtExceptionHandler {

        /**
         * 处理线程中未捕获的异常.
         *
         * @param t 发生异常的线程.
         * @param e 未捕获的异常对象.
         */
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            AbstractJavaScheduler.this.plugin.getLogger().warning("Thread " + t.getName() + " threw an uncaught exception: " + e.getMessage());
        }
    }
}
