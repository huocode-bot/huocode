package io.huocode.api.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoChurn;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChurnParserTest {

  @Test
  void isBot_matches_known_bot_patterns() {
    assertTrue(ChurnParser.isBot("dependabot[bot]"));
    assertTrue(ChurnParser.isBot("renovate[bot]"));
    assertTrue(ChurnParser.isBot("github-actions[bot]"));
    assertTrue(ChurnParser.isBot("Renovate"));
    assertTrue(ChurnParser.isBot("Dependabot"));
    assertFalse(ChurnParser.isBot("octocat"));
    assertFalse(ChurnParser.isBot("Alice Smith"));
  }

  @Test
  void parse_ignores_bots_and_aggregates_churn() {
    String log =
        "\u001eabc111\u001f2024-01-01T12:00:00+01:00\u001fAlice\n"
            + "1\t1\tsrc/A.java\n"
            + "5\t0\tREADME.md\n"
            + "\u001eabc222\u001f2024-01-02T12:00:00+01:00\u001fBob\n"
            + "2\t2\tsrc/A.java\n"
            + "\u001eabc333\u001f2024-01-03T12:00:00+01:00\u001fdependabot[bot]\n"
            + "99\t0\tpackage-lock.json\n";

    RepoChurn churn = ChurnParser.parse(log);

    assertEquals(2, churn.window().commitsAnalyzed());
    assertEquals(2, churn.window().distinctAuthors());
    assertEquals(Instant.parse("2024-01-01T11:00:00Z"), churn.window().oldestCommitDate());
    assertEquals(Instant.parse("2024-01-02T11:00:00Z"), churn.window().newestCommitDate());
    assertEquals(new Churn(2, 2, 3, 3), churn.churnByPath().get("src/A.java"));
    assertEquals(new Churn(1, 1, 5, 0), churn.churnByPath().get("README.md"));
    assertFalse(churn.churnByPath().containsKey("package-lock.json"));
  }

  @Test
  void parse_handles_binary_and_rename_lines() {
    String log =
        "\u001eabc111\u001f2024-01-01T12:00:00+01:00\u001fAlice\n"
            + "-\t-\tassets/logo.png\n"
            + "1\t2\told.txt => new.txt\n";

    RepoChurn churn = ChurnParser.parse(log);

    assertEquals(new Churn(1, 1, 0, 0), churn.churnByPath().get("assets/logo.png"));
    assertEquals(new Churn(1, 1, 1, 2), churn.churnByPath().get("new.txt"));
    assertEquals(2, churn.churnByPath().size());
  }

  @Test
  void parse_returns_empty_window_for_blank_input() {
    RepoChurn churn = ChurnParser.parse("");
    assertEquals(0, churn.window().commitsAnalyzed());
    assertEquals(0, churn.window().distinctAuthors());
    assertTrue(churn.churnByPath().isEmpty());
  }

  @Test
  void aggregate_attributes_history_before_a_rename_to_the_current_path() {
    List<ChurnParser.CommitChurn> newestFirst =
        List.of(
            new ChurnParser.CommitChurn(
                "Bob", null, List.of(new ChurnParser.PathChange("b/F.java", 2, 0))),
            new ChurnParser.CommitChurn(
                "Bob", null, List.of(new ChurnParser.PathChange("b/F.java", "a/F.java", 0, 0))),
            new ChurnParser.CommitChurn(
                "Alice", null, List.of(new ChurnParser.PathChange("a/F.java", 10, 3))));

    RepoChurn churn = ChurnParser.aggregate(newestFirst);

    assertEquals(new Churn(3, 2, 12, 3), churn.churnByPath().get("b/F.java"));
    assertFalse(churn.churnByPath().containsKey("a/F.java"));
  }

  @Test
  void countPatchLines_skips_headers_and_blank_lines_but_keeps_dash_prefixed_content() {
    String patch =
        "diff --git a/A.java b/A.java\n"
            + "--- a/A.java\n"
            + "+++ b/A.java\n"
            + "@@ -1,3 +1,3 @@\n"
            + "---i;\n"
            + "+++i;\n"
            + "+\n"
            + "-   \n"
            + "\\ No newline at end of file\n";

    assertEquals(new ChurnParser.LineCounts(1, 1), ChurnParser.countPatchLines(patch));
  }

  @Test
  void aggregate_follows_a_rename_by_ancestry_even_when_the_parent_is_dated_after_its_child() {
    // Input in timestamp order: W (edits a/F.java) is dated after X (renames it to b/F.java)
    // although W is X's ancestor, through the merge M.
    List<ChurnParser.CommitChurn> byDate =
        List.of(
            new ChurnParser.CommitChurn(
                "W",
                List.of("V"),
                "Alice",
                null,
                List.of(new ChurnParser.PathChange("a/F.java", 5, 1))),
            new ChurnParser.CommitChurn(
                "X",
                List.of("M"),
                "Bob",
                null,
                List.of(new ChurnParser.PathChange("b/F.java", "a/F.java", 0, 0))),
            new ChurnParser.CommitChurn("M", List.of("W", "Z"), "Bob", null, List.of()));

    RepoChurn churn = ChurnParser.aggregate(byDate);

    assertEquals(new Churn(2, 2, 5, 1), churn.churnByPath().get("b/F.java"));
    assertFalse(churn.churnByPath().containsKey("a/F.java"));
    // The merge carries ancestry only.
    assertEquals(2, churn.window().commitsAnalyzed());
  }
}
