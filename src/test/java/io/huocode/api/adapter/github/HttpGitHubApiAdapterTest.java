package io.huocode.api.adapter.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.exception.GitHubRateLimitReachedException;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
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
  void churnForPath_filters_bots_and_counts_authors() {
    stub(
        "/repos/owner/repo/commits",
        200,
        "["
            + commitJson("c1", "octocat", "2024-01-01T10:00:00Z")
            + ","
            + commitJson("c2", "bob", "2024-01-02T10:00:00Z")
            + ","
            + commitJson("c3", "dependabot[bot]", "2024-01-03T10:00:00Z")
            + "]");
    assertEquals(new Churn(2, 2), adapter.churnForPath(REPO, "src/A.java", 500));
  }

  @Test
  void churnForPath_paginates_until_window_is_filled() {
    List<String> requestedPages = new ArrayList<>();
    server.createContext(
        "/repos/owner/repo/commits",
        exchange -> {
          Matcher matcher = PAGE_PARAM.matcher(exchange.getRequestURI().getRawQuery());
          String page = matcher.find() ? matcher.group(1) : "1";
          requestedPages.add(page);
          String body = "1".equals(page) ? commitsJson(100, "octocat") : "[]";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          try (var os = exchange.getResponseBody()) {
            os.write(bytes);
          }
          exchange.close();
        });
    assertEquals(new Churn(100, 1), adapter.churnForPath(REPO, "src/A.java", 500));
    assertEquals(List.of("1", "2"), requestedPages);
  }

  @Test
  void analysisWindow_collects_dates_and_authors() {
    stub(
        "/repos/owner/repo/commits",
        200,
        "["
            + commitJson("c1", "octocat", "2024-01-01T10:00:00Z")
            + ","
            + commitJson("c2", "bob", "2024-01-02T10:00:00Z")
            + ","
            + commitJson("c3", "renovate[bot]", "2024-01-03T10:00:00Z")
            + "]");
    AnalysisWindowData window = adapter.analysisWindow(REPO, 500);
    assertEquals(2, window.commitsAnalyzed());
    assertEquals(2, window.distinctAuthors());
    assertEquals(Instant.parse("2024-01-01T10:00:00Z"), window.oldestCommitDate());
    assertEquals(Instant.parse("2024-01-02T10:00:00Z"), window.newestCommitDate());
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
  }

  @Test
  void throws_illegal_state_on_5xx() {
    stub("/repos/owner/repo", 500, "{}");
    assertThrows(IllegalStateException.class, () -> adapter.defaultBranch(REPO));
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
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var os = exchange.getResponseBody()) {
            os.write(bytes);
          }
          exchange.close();
        });
  }

  private static String commitJson(String sha, String login, String date) {
    return "{\"sha\":\""
        + sha
        + "\",\"author\":{\"login\":\""
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
