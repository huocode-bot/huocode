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

class JGitScmAdapterTest {

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
          2,
          10);

  @TempDir Path temp;

  private FileUrlScmAdapter fileAdapter;

  @BeforeEach
  void setUp() {
    fileAdapter = new FileUrlScmAdapter(PROPERTIES, temp.resolve("source"));
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
    assertEquals(new Churn(2, 1, 2, 1), churn.churnByPath().get("src/A.java"));
    assertEquals(new Churn(1, 1, 1, 0), churn.churnByPath().get("README.md"));

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

  @Test
  void clone_is_redone_deeper_when_merges_shorten_the_commit_window() throws IOException {
    Path source = temp.resolve("source");
    TestGitRepos.init(source);
    TestGitRepos.run(source, "checkout", "-q", "-b", "main");
    TestGitRepos.write(source, "Base.java", "class Base {}\n");
    TestGitRepos.commit(source, "base");
    // Every commit of main is a merge of a one-commit feature branch, deleted once merged.
    for (int i = 1; i <= 4; i++) {
      TestGitRepos.run(source, "checkout", "-q", "-b", "feature" + i);
      TestGitRepos.write(source, "F" + i + ".java", "class F" + i + " {}\n");
      TestGitRepos.commit(source, "feature " + i);
      TestGitRepos.run(source, "checkout", "-q", "main");
      TestGitRepos.run(source, "merge", "-q", "--no-ff", "-m", "merge " + i, "feature" + i);
      TestGitRepos.run(source, "branch", "-q", "-d", "feature" + i);
    }
    AnalysisProperties windowOfThree =
        new AnalysisProperties(
            "",
            Duration.ofSeconds(5),
            300,
            20000,
            15,
            3,
            10,
            Duration.ofSeconds(10),
            1048576,
            Duration.ofHours(48),
            60,
            Duration.ofMinutes(15),
            10,
            60,
            2,
            10);
    FileUrlScmAdapter adapter = new FileUrlScmAdapter(windowOfThree, source);
    Path cloneDir = adapter.clone(new RepoUrl("owner", "repo"), temp.resolve("target/clone"));

    // A depth-4 clone (window + 1) stops at merge 1 and feature 2: only features 4 and 3 could
    // be diffed. The clone is redone deeper, so the window holds 3 commits.
    assertEquals(3, adapter.logNumstat(cloneDir).window().commitsAnalyzed());
  }
}
