package io.huocode.api.utils;

import io.huocode.api.exception.GitCommandFailedException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TimedGitCommand {

  private TimedGitCommand() {}

  public static <T> T run(Duration timeout, Callable<T> task) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<T> future = executor.submit(task);
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitCommandFailedException("git", "interrupted");
    } catch (ExecutionException e) {
      throw unwrap(e);
    } catch (TimeoutException e) {
      throw new GitCommandFailedException("git", "command timed out after " + timeout);
    } finally {
      executor.shutdownNow();
    }
  }

  public static void run(Duration timeout, Runnable task) {
    run(timeout, () -> rescuingRun(task));
  }

  private static Void rescuingRun(Runnable task) {
    task.run();
    return null;
  }

  private static RuntimeException unwrap(ExecutionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof GitCommandFailedException gitCommandFailedException) {
      return gitCommandFailedException;
    }
    return new GitCommandFailedException(
        "git", cause == null ? "command failed" : cause.getMessage());
  }
}
