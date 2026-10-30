package io.huocode.api.adapter.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.TestGitRepos;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoChurn;
import io.huocode.api.utils.ChurnParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JGitChurnReaderTest {

  @TempDir Path temp;

  @Test
  void read_skips_the_shallow_clone_boundary_commit() throws IOException {
    Path source = temp.resolve("source");
    TestGitRepos.init(source);
    TestGitRepos.write(source, "A.java", "class A {\n  int a;\n  int b;\n}\n");
    TestGitRepos.commit(source, "first");
    TestGitRepos.write(source, "A.java", "class A {\n  int a2;\n  int b;\n}\n");
    TestGitRepos.commit(source, "second");
    TestGitRepos.write(source, "A.java", "class A {\n  int a2;\n  int b2;\n}\n");
    TestGitRepos.commit(source, "third");
    Path shallow = temp.resolve("shallow");
    TestGitRepos.run(
        temp, "clone", "-q", "--depth", "2", source.toUri().toString(), shallow.toString());

    RepoChurn churn = ChurnParser.aggregate(new JGitChurnReader(500).read(shallow).commits());

    // "second" is the boundary of the depth-2 clone: its parent is missing, so it cannot be
    // diffed and must not be counted as adding the whole file.
    assertEquals(1, churn.window().commitsAnalyzed());
    assertEquals(new Churn(1, 1, 1, 1), churn.churnByPath().get("A.java"));
  }

  @Test
  void read_follows_renames_without_counting_the_move_as_churn() throws IOException {
    Path repo = temp.resolve("repo");
    TestGitRepos.init(repo);
    String body = "class A {\n" + "  int x;\n".repeat(40) + "}\n";
    TestGitRepos.write(repo, "old/A.java", body);
    TestGitRepos.commit(repo, "create");
    TestGitRepos.write(repo, "old/A.java", body.replaceFirst("int x;", "int y;"));
    TestGitRepos.commit(repo, "edit before move");
    Files.createDirectories(repo.resolve("src"));
    TestGitRepos.run(repo, "mv", "old/A.java", "src/A.java");
    TestGitRepos.commit(repo, "move");

    RepoChurn churn = ChurnParser.aggregate(new JGitChurnReader(500).read(repo).commits());

    // create: +42, edit: +1/-1, pure move: 0 — all attributed to the current path.
    assertEquals(new Churn(3, 1, 43, 1), churn.churnByPath().get("src/A.java"));
    assertEquals(null, churn.churnByPath().get("old/A.java"));
  }

  @Test
  void scan_reports_a_window_cut_short_by_the_shallow_boundary() throws IOException {
    Path source = temp.resolve("source");
    TestGitRepos.init(source);
    TestGitRepos.run(source, "checkout", "-q", "-b", "main");
    TestGitRepos.write(source, "Base.java", "class Base {}\n");
    TestGitRepos.commit(source, "base");
    for (int i = 1; i <= 4; i++) {
      TestGitRepos.run(source, "checkout", "-q", "-b", "feature" + i);
      TestGitRepos.write(source, "F" + i + ".java", "class F" + i + " {}\n");
      TestGitRepos.commit(source, "feature " + i);
      TestGitRepos.run(source, "checkout", "-q", "main");
      TestGitRepos.run(source, "merge", "-q", "--no-ff", "-m", "merge " + i, "feature" + i);
    }
    Path shallow = temp.resolve("shallow");
    TestGitRepos.run(
        temp, "clone", "-q", "--depth", "4", source.toUri().toString(), shallow.toString());

    // Depth 4 stops at merge 1 and feature 2: only features 4 and 3 can be diffed.
    JGitChurnReader.Window window = new JGitChurnReader(3).scan(shallow);

    assertTrue(window.truncated());
    assertEquals(2, window.commits().stream().filter(commit -> !commit.isMerge()).count());
    assertFalse(new JGitChurnReader(2).scan(shallow).truncated());
  }

  @Test
  void read_counts_the_same_lines_as_the_api_fallback_diff() throws IOException {
    String before = "class A {\n  int a;\n\n  int b;\n  int c;\n}\n";
    String after = "class A {\n  int a2;\n\n\n  int c;\n  int d;\n  int e;\n}\n";
    Path repo = temp.resolve("repo");
    TestGitRepos.init(repo);
    TestGitRepos.write(repo, "A.java", before);
    TestGitRepos.commit(repo, "before");
    TestGitRepos.write(repo, "A.java", after);
    TestGitRepos.commit(repo, "after");

    ChurnParser.PathChange change =
        new JGitChurnReader(1).read(repo).commits().get(0).changes().get(0);

    ChurnParser.LineCounts api = ChurnParser.countChangedLines(before, after);
    assertEquals(api.added(), change.added());
    assertEquals(api.deleted(), change.deleted());
    // a2, d, e added (the blank line is not counted); a, b deleted.
    assertEquals(new ChurnParser.LineCounts(3, 2), api);
  }

  @Test
  void read_follows_a_rename_with_edits_at_gits_50_percent_similarity() throws IOException {
    Path repo = temp.resolve("repo");
    TestGitRepos.init(repo);
    StringBuilder before = new StringBuilder();
    StringBuilder after = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      before.append("  int field").append(i).append(" = ").append(i).append(";\n");
      // 11 of 20 lines kept: about 55% similar, a rename for git (50) but not for JGit's 60.
      after.append(
          i < 11 ? "  int field" + i + " = " + i + ";\n" : "  long other" + i + " = " + i + ";\n");
    }
    TestGitRepos.write(repo, "old/A.java", before.toString());
    TestGitRepos.commit(repo, "create");
    Files.createDirectories(repo.resolve("src"));
    TestGitRepos.run(repo, "mv", "old/A.java", "src/A.java");
    TestGitRepos.write(repo, "src/A.java", after.toString());
    TestGitRepos.commit(repo, "move and edit");

    RepoChurn churn = ChurnParser.aggregate(new JGitChurnReader(500).read(repo).commits());

    // create: +20; move and edit: +9 / -9 as a rename, not +20 as a new file.
    assertEquals(new Churn(2, 1, 29, 9), churn.churnByPath().get("src/A.java"));
  }
}
