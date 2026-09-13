package io.huocode.api.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoChurn;
import java.time.Instant;
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
}
