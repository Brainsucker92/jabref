package org.jabref;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible for managing of all threads (except GUI threads) in JabRef
 * @deprecated Use {@link org.jabref.gui.util.BackgroundTask} instead
 */
@Deprecated
public class JabRefExecutorService {

    public static final JabRefExecutorService INSTANCE = new JabRefExecutorService();
    private static final Logger LOGGER = LoggerFactory.getLogger(JabRefExecutorService.class);
    private final ExecutorService executorService = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("JabRef CachedThreadPool");
        thread.setUncaughtExceptionHandler(new FallbackExceptionHandler());
        return thread;
    });
    private final ExecutorService lowPriorityExecutorService = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("JabRef LowPriorityCachedThreadPool");
        thread.setUncaughtExceptionHandler(new FallbackExceptionHandler());
        return thread;
    });
    private final Timer timer = new Timer("timer", true);
    private Thread remoteThread;

    private JabRefExecutorService() {
    }

    public void execute(Runnable command) {
        Objects.requireNonNull(command);
        executorService.execute(command);
    }

    public void executeAndWait(Runnable command) {
        Objects.requireNonNull(command);
        Future<?> future = executorService.submit(command);
        try {
            future.get();
        } catch (InterruptedException ignored) {
            // Ignored
        } catch (ExecutionException e) {
            LOGGER.error("Problem executing command", e);
        }
    }

    /**
     * Executes a callable task that provides a return value after the calculation is done.
     *
     * @param command The task to execute.
     * @return A Future object that provides the returning value.
     */
    public <T> Future<T> execute(Callable<T> command) {
        Objects.requireNonNull(command);
        return executorService.submit(command);
    }

    /**
     * Executes a collection of callable tasks and returns a List of the resulting Future objects after the calculation
     * is done.
     *
     * @param tasks The tasks to execute
     * @return A List of Future objects that provide the returning values.
     */
    public <T> List<Future<T>> executeAll(Collection<Callable<T>> tasks) {
        Objects.requireNonNull(tasks);
        try {
            return executorService.invokeAll(tasks);
        } catch (InterruptedException exception) {
            // Ignored
            return Collections.emptyList();
        }
    }

    public <T> List<Future<T>> executeAll(Collection<Callable<T>> tasks, int timeout, TimeUnit timeUnit) {
        Objects.requireNonNull(tasks);
        try {
            return executorService.invokeAll(tasks, timeout, timeUnit);
        } catch (InterruptedException exception) {
            // Ignored
            return Collections.emptyList();
        }
    }

    public <T> CompletableFuture<T> execute(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executorService);
    }

    public <T> CompletableFuture<T> executeInterruptible(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, lowPriorityExecutorService);
    }

    public <T> List<CompletableFuture<T>> executeAllInterruptible(List<Supplier<T>> supplierList) {
        return supplierList.stream().map(supplier -> CompletableFuture.supplyAsync(supplier, lowPriorityExecutorService))
                           .collect(Collectors.toList());
    }

    public <T> List<CompletableFuture<T>> executeAll(List<Supplier<T>> supplierList) {
        return supplierList.stream().map(supplier -> CompletableFuture.supplyAsync(supplier, executorService))
                           .collect(Collectors.toList());
    }

    public void executeInterruptableTask(final Runnable runnable, String taskName) {
        this.lowPriorityExecutorService.execute(new NamedRunnable(taskName, runnable));
    }

    public void executeInterruptableTaskAndWait(Runnable runnable) {
        Objects.requireNonNull(runnable);

        Future<?> future = lowPriorityExecutorService.submit(runnable);
        try {
            future.get();
        } catch (InterruptedException ignored) {
            // Ignored
        } catch (ExecutionException e) {
            LOGGER.error("Problem executing command", e);
        }
    }

    public void manageRemoteThread(Thread thread) {
        if (this.remoteThread != null) {
            throw new IllegalStateException("Remote thread is already attached");
        } else {
            this.remoteThread = thread;
            remoteThread.start();
        }
    }

    public void stopRemoteThread() {
        if (remoteThread != null) {
            remoteThread.interrupt();
            remoteThread = null;
        }
    }

    public void submit(TimerTask timerTask, long millisecondsDelay) {
        timer.schedule(timerTask, millisecondsDelay);
    }

    public void shutdownEverything() {
        // those threads will be allowed to finish
        this.executorService.shutdown();
        //those threads will be interrupted in their current task
        this.lowPriorityExecutorService.shutdownNow();
        // kill the remote thread
        stopRemoteThread();
        timer.cancel();
    }

    private class NamedRunnable implements Runnable {

        private final String name;

        private final Runnable task;

        private NamedRunnable(String name, Runnable runnable) {
            this.name = name;
            this.task = runnable;
        }

        @Override
        public void run() {
            final String orgName = Thread.currentThread().getName();
            Thread.currentThread().setName(name);
            try {
                task.run();
            } finally {
                Thread.currentThread().setName(orgName);
            }
        }
    }
}
