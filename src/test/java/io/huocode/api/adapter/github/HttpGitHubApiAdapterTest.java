package io.huocode.api.adapter.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.exception.GitHubRateLimitReachedException;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoChurn;
import io.huocode.api.model.RepoUrl;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpGitHubApiAdapterTest {

  private static final RepoUrl REPO = new RepoUrl("owner", "repo");
  private static final String SHA = "sha123";
  private static final Pattern PAGE_PARAM = Pattern.compile("(?:^|&)page=(\\d+)");

  private HttpServer server;
  private HttpGitHubApiAdapter adapter;
  private final List<String> authorizationHeaders = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.start();
    AnalysisProperties properties =
        new AnalysisProperties(
            "test-token",
            Duration.ofSeconds(5),
            300,
            20000,
            15,
            500,
            10,
            Duration.ofSeconds(5),
            1048576,
            Duration.ofHours(48),
            60,
            Duration.ofMinutes(15),
            10,
            60,
            2,
            10);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    adapter = new HttpGitHubApiAdapter(new GitHubApiHttp(properties, new ObjectMapper(), baseUrl));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void repoExists_returns_true_on_200() {
    stub("/repos/owner/repo", 200, "{\"default_branch\":\"main\"}");
    assertTrue(adapter.repoExists(REPO));
  }

  @Test
  void repoExists_returns_false_on_404() {
    stub("/repos/owner/repo", 404, "{}");
    assertFalse(adapter.repoExists(REPO));
  }

  @Test
  void defaultBranch_parses_field() {
    stub("/repos/owner/repo", 200, "{\"default_branch\":\"main\"}");
    assertEquals("main", adapter.defaultBranch(REPO));
  }

  @Test
  void latestCommitSha_parses_field() {
    stub("/repos/owner/repo", 200, "{\"default_branch\":\"main\"}");
    stub("/repos/owner/repo/commits/main", 200, "{\"sha\":\"abc123\"}");
    assertEquals("abc123", adapter.latestCommitSha(REPO));
  }

  @Test
  void listAllPaths_returns_only_blobs() {
    stub(
        "/repos/owner/repo/git/trees/" + SHA,
        200,
        "{\"truncated\":false,"
            + "\"tree\":["
            + "{\"path\":\"src\",\"type\":\"tree\"},"
            + "{\"path\":\"src/A.java\",\"type\":\"blob\"},"
            + "{\"path\":\"B.java\",\"type\":\"blob\"}]}");
    assertEquals(List.of("src/A.java", "B.java"), adapter.listAllPaths(REPO, SHA));
  }

  @Test
  void fileCount_counts_only_blobs() {
    stub(
        "/repos/owner/repo/git/trees/" + SHA,
        200,
        "{\"truncated\":false,"
            + "\"tree\":["
            + "{\"path\":\"src\",\"type\":\"tree\"},"
            + "{\"path\":\"A.java\",\"type\":\"blob\"},"
            + "{\"path\":\"B.java\",\"type\":\"blob\"}]}");
    assertEquals(2, adapter.fileCount(REPO, SHA));
  }

  @Test
  void rawContent_returns_body() {
    stub("/repos/owner/repo/contents/src/A.java", 200, "public class A {}");
    assertEquals("public class A {}", adapter.rawContent(REPO, "src/A.java", SHA));
  }

  @Test
  void repoChurn_reads_the_window_of_the_analyzed_commit_like_the_clone_path() {
    List<String> unstubbedRequests = new ArrayList<>();
    server.createContext(
        "/repos/owner/repo/commits",
        exchange -> {
          unstubbedRequests.add(exchange.getRequestURI().toString());
          String body =
              "["
                  + commitJson("c3", "bob", "2024-01-04T10:00:00Z")
                  + ","
                  + commitJson("c2", "bob", "2024-01-03T10:00:00Z")
                  + ","
                  + mergeJson("m1")
                  + ","
                  + commitJson("c1", "octocat", "2024-01-02T10:00:00Z")
                  + ","
                  + commitJson("c0", "dependabot[bot]", "2024-01-01T10:00:00Z")
                  + "]";
          respond(exchange, 200, body);
        });
    // c3 is a pure move: no patch, no lines.
    stub(
        "/repos/owner/repo/commits/c3",
        200,
        "{\"files\":[{\"filename\":\"src/A.java\",\"status\":\"renamed\","
            + "\"previous_filename\":\"old/A.java\",\"additions\":0,\"deletions\":0}]}");
    // The blank added line is not counted, as in the clone path.
    stub(
        "/repos/owner/repo/commits/c2",
        200,
        patchDetailJson("src/A.java", "@@ -1 +1,2 @@\\n-a\\n+b\\n+"));
    stub(
        "/repos/owner/repo/commits/c1",
        200,
        patchDetailJson("old/A.java", "@@ -0,0 +1,3 @@\\n+x\\n+y\\n+z"));
    stub("/repos/owner/repo/commits/c0", 200, patchDetailJson("pom.xml", "@@ -1 +1 @@\\n-1\\n+2"));

    RepoChurn churn = adapter.repoChurn(REPO, SHA, 500);

    assertEquals(new Churn(3, 2, 4, 1), churn.churnByPath().get("src/A.java"));
    assertFalse(churn.churnByPath().containsKey("old/A.java"));
    assertFalse(churn.churnByPath().containsKey("pom.xml"));
    assertEquals(3, churn.window().commitsAnalyzed());
    assertEquals(2, churn.window().distinctAuthors());
    assertEquals(Instant.parse("2024-01-02T10:00:00Z"), churn.window().oldestCommitDate());
    assertEquals(Instant.parse("2024-01-04T10:00:00Z"), churn.window().newestCommitDate());
    assertEquals(
        List.of("/repos/owner/repo/commits?sha=" + SHA + "&per_page=100&page=1"),
        unstubbedRequests,
        "the window starts at the analyzed commit and merge commits are never diffed");
  }

  @Test
  void repoChurn_paginates_until_window_is_filled() {
    List<String> requestedPages = new ArrayList<>();
    server.createContext(
        "/repos/owner/repo/commits",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          Matcher matcher = PAGE_PARAM.matcher(query == null ? "" : query);
          String page = matcher.find() ? matcher.group(1) : "1";
          requestedPages.add(page);
          respond(exchange, 200, "1".equals(page) ? commitsJson(100, "octocat") : "[]");
        });
    // Commit-detail calls hit the same handler and get an array with no "files": 0 lines.
    RepoChurn churn = adapter.repoChurn(REPO, SHA, 500);
    assertEquals(100, churn.window().commitsAnalyzed());
    assertEquals(List.of("1", "2"), requestedPages.stream().distinct().toList());
  }

  @Test
  void repoChurn_fails_instead_of_scoring_zero_churn_when_a_diff_is_unavailable() {
    stub(
        "/repos/owner/repo/commits",
        200,
        "[" + commitJson("c1", "octocat", "2024-01-01T10:00:00Z") + "]");
    stub(
        Map.of("x-ratelimit-remaining", "0"),
        "/repos/owner/repo/commits/c1",
        403,
        "{\"message\":\"API rate limit exceeded\"}");
    assertThrows(GitHubRateLimitReachedException.class, () -> adapter.repoChurn(REPO, SHA, 500));
  }

  @Test
  void repoChurn_diffs_both_versions_when_github_omits_the_patch() {
    stub(
        "/repos/owner/repo/commits",
        200,
        "[" + commitJson("c1", "octocat", "2024-01-01T10:00:00Z") + "]");
    // GitHub's raw counts (3 / 1) include the added blank line.
    stub(
        "/repos/owner/repo/commits/c1",
        200,
        "{\"files\":[{\"filename\":\"src/Big.java\",\"status\":\"modified\","
            + "\"additions\":3,\"deletions\":1}]}");
    server.createContext(
        "/repos/owner/repo/contents/src/Big.java",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          respond(exchange, 200, query.equals("ref=p") ? "a\nb\nc\n" : "a\nB\n\nc\nd\n");
        });

    RepoChurn churn = adapter.repoChurn(REPO, SHA, 500);

    assertEquals(new Churn(1, 1, 2, 1), churn.churnByPath().get("src/Big.java"));
  }

  @Test
  void sends_bearer_token_header() {
    stub("/repos/owner/repo", 200, "{\"default_branch\":\"main\"}");
    adapter.defaultBranch(REPO);
    assertEquals(List.of("Bearer test-token"), authorizationHeaders);
  }

  @Test
  void throws_repo_not_found_on_404() {
    stub("/repos/owner/repo", 404, "{}");
    assertThrows(RepoNotFoundException.class, () -> adapter.defaultBranch(REPO));
  }

  @Test
  void throws_rate_limit_exceeded_on_403_with_zero_remaining() {
    stub(
        Map.of("x-ratelimit-remaining", "0"),
        "/repos/owner/repo",
        403,
        "{\"message\":\"API rate limit exceeded for 1.2.3.4\"}");
    assertThrows(GitHubRateLimitReachedException.class, () -> adapter.defaultBranch(REPO));
    // The hourly quota is not retried: it only resets at its window.
    assertEquals(1, authorizationHeaders.size());
  }

  @Test
  void throws_illegal_state_on_5xx_after_three_attempts() {
    stub(Map.of("retry-after", "0"), "/repos/owner/repo", 500, "{}");
    assertThrows(IllegalStateException.class, () -> adapter.defaultBranch(REPO));
    assertEquals(3, authorizationHeaders.size());
  }

  @Test
  void retries_a_transient_server_error_until_it_passes() {
    List<Integer> statuses = new ArrayList<>(List.of(502, 502, 200));
    server.createContext(
        "/repos/owner/repo",
        exchange -> {
          exchange.getResponseHeaders().add("retry-after", "0");
          respond(exchange, statuses.remove(0), "{\"default_branch\":\"main\"}");
        });
    assertEquals("main", adapter.defaultBranch(REPO));
    assertTrue(statuses.isEmpty());
  }

  @Test
  void retries_the_secondary_rate_limit_but_not_the_primary_one() {
    List<Integer> statuses = new ArrayList<>(List.of(403, 200));
    server.createContext(
        "/repos/owner/repo",
        exchange -> {
          exchange.getResponseHeaders().add("retry-after", "0");
          int status = statuses.remove(0);
          respond(
              exchange,
              status,
              status == 403
                  ? "{\"message\":\"You have exceeded a secondary rate limit\"}"
                  : "{\"default_branch\":\"main\"}");
        });
    assertEquals("main", adapter.defaultBranch(REPO));
    assertTrue(statuses.isEmpty());
  }

  private void stub(String path, int status, String body) {
    stub(Map.of(), path, status, body);
  }

  private void stub(Map<String, String> headers, String path, int status, String body) {
    server.createContext(
        path,
        exchange -> {
          authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
          headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
          respond(exchange, status, body);
        });
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    exchange.close();
  }

  private static String commitJson(String sha, String login, String date) {
    return "{\"sha\":\""
        + sha
        + "\",\"parents\":[{\"sha\":\"p\"}],\"author\":{\"login\":\""
        + login
        + "\"},"
        + "\"commit\":{\"author\":{\"name\":\""
        + login
        + "\",\"date\":\""
        + date
        + "\"},"
        + "\"committer\":{\"name\":\""
        + login
        + "\",\"date\":\""
        + date
        + "\"}}}";
  }

  private static String patchDetailJson(String path, String patch) {
    return "{\"files\":[{\"filename\":\""
        + path
        + "\",\"status\":\"modified\",\"patch\":\""
        + patch
        + "\"}]}";
  }

  private static String mergeJson(String sha) {
    return "{\"sha\":\""
        + sha
        + "\",\"parents\":[{\"sha\":\"p1\"},{\"sha\":\"p2\"}],"
        + "\"commit\":{\"author\":{\"name\":\"bob\",\"date\":\"2024-01-05T10:00:00Z\"}}}";
  }

  private static String commitsJson(int count, String login) {
    StringBuilder body = new StringBuilder("[");
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        body.append(",");
      }
      body.append(commitJson("sha" + i, login, "2024-01-01T10:00:00Z"));
    }
    return body.append("]").toString();
  }
}
