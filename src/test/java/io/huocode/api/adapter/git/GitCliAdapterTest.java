package io.huocode.api.adapter.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.TestGitRepos;
import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoChurn;
import io.huocode.api.model.RepoUrl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCliAdapterTest {

  private static final AnalysisProperties PROPERTIES =
      new AnalysisProperties(
          "",
          Duration.ofSeconds(5),
          300,
          20000,
          15,
          500,
          10,
          Duration.ofSeconds(10),
          1048576,
          Duration.ofHours(48),
          60,
          Duration.ofMinutes(15),
          10,
          60,
          2);

  @TempDir Path temp;

  private FileUrlGitCliAdapter fileAdapter;

  @BeforeEach
  void setUp() {
    fileAdapter = new FileUrlGitCliAdapter(PROPERTIES, temp.resolve("source"));
  }

  @Test
  void clone_tracks_files_and_reads_churn_and_sha() throws IOException {
    Path source = temp.resolve("source");
    TestGitRepos.init(source);
    TestGitRepos.write(source, "src/A.java", "class A {}\n");
    TestGitRepos.write(source, "README.md", "readme\n");
    TestGitRepos.commit(source, "first commit");
    TestGitRepos.write(source, "src/A.java", "class A { int x; }\n");
    TestGitRepos.commit(source, "second commit");

    Path target = temp.resolve("target");
    Files.createDirectories(target);
    Path cloneDir = fileAdapter.clone(new RepoUrl("owner", "repo"), target.resolve("clone"));

    assertEquals(List.of("README.md", "src/A.java"), fileAdapter.trackedFiles(cloneDir));
    assertTrue(Files.exists(cloneDir.resolve("src/A.java")));
    assertTrue(Files.exists(cloneDir.resolve("README.md")));

    RepoChurn churn = fileAdapter.logNumstat(cloneDir);
    assertEquals(2, churn.window().commitsAnalyzed());
    assertEquals(new Churn(2, 1), churn.churnByPath().get("src/A.java"));
    assertEquals(new Churn(1, 1), churn.churnByPath().get("README.md"));

    assertEquals(40, fileAdapter.latestCommitSha(cloneDir).length());
  }

  @Test
  void logNumstat_excludes_bot_commits() throws IOException {
    Path source = temp.resolve("source");
    TestGitRepos.init(source);
    TestGitRepos.write(source, "A.java", "class A {}\n");
    TestGitRepos.commit(source, "human commit");
    TestGitRepos.write(source, "A.java", "class A { int y; }\n");
    TestGitRepos.run(
        source, "-c", "user.name=dependabot[bot]", "-c", "user.email=bot@example.com", "add", ".");
    TestGitRepos.run(
        source,
        "-c",
        "user.name=dependabot[bot]",
        "-c",
        "user.email=bot@example.com",
        "commit",
        "-q",
        "-m",
        "bot commit");

    Path target = temp.resolve("target");
    Files.createDirectories(target);
    Path cloneDir = fileAdapter.clone(new RepoUrl("owner", "repo"), target.resolve("clone"));

    RepoChurn churn = fileAdapter.logNumstat(cloneDir);
    assertEquals(1, churn.window().commitsAnalyzed());
    assertEquals(1, churn.churnByPath().get("A.java").commits());
  }
}
