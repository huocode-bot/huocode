package io.huocode.api;

import io.huocode.api.utils.GitProcessUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class TestGitRepos {

  public static final Duration TIMEOUT = Duration.ofSeconds(10);

  private TestGitRepos() {}

  public static void init(Path dir) throws IOException {
    Files.createDirectories(dir);
    run(dir, "init", "-q");
    run(dir, "config", "user.email", "test@example.com");
    run(dir, "config", "user.name", "Test User");
  }

  public static void write(Path dir, String name, String content) throws IOException {
    Path file = dir.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  public static void commit(Path dir, String message) {
    run(dir, "add", ".");
    run(dir, "commit", "-q", "-m", message);
  }

  public static void run(Path dir, String... args) {
    GitProcessUtils.run(dir, TIMEOUT, args);
  }
}
