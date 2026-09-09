package io.huocode.api.utils;

import io.huocode.api.exception.GitCommandFailedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GitProcessUtils {

  private GitProcessUtils() {}

  public static String run(Path workingDirectory, Duration timeout, String... gitArgs) {
    String commandLine = "git " + String.join(" ", gitArgs);
    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(command(gitArgs));
      builder.directory(workingDirectory.toFile());
      builder.redirectErrorStream(true);
      builder.environment().put("GIT_TERMINAL_PROMPT", "0");
      process = builder.start();
    } catch (IOException e) {
      throw new GitCommandFailedException(commandLine, "could not start git: " + e.getMessage());
    }
    var output = new ByteArrayOutputStream();
    Thread reader = new Thread(() -> drain(process, output));
    reader.start();
    try {
      boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        process.destroyForcibly();
        reader.join();
        throw new GitCommandFailedException(
            commandLine, "timed out after " + timeout.toMillis() + "ms");
      }
      reader.join();
      String out = output.toString(StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new GitCommandFailedException(commandLine, out);
      }
      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      reader.interrupt();
      process.destroyForcibly();
      throw new GitCommandFailedException(commandLine, "interrupted");
    }
  }

  private static List<String> command(String[] gitArgs) {
    List<String> command = new ArrayList<>(gitArgs.length + 1);
    command.add("git");
    command.addAll(Arrays.asList(gitArgs));
    return command;
  }

  private static void drain(Process process, ByteArrayOutputStream output) {
    try (var in = process.getInputStream()) {
      in.transferTo(output);
    } catch (IOException ignored) {
      // process may have been destroyed while waiting
    }
  }
}
