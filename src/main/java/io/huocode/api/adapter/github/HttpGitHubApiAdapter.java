package io.huocode.api.adapter.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.exception.GitHubRateLimitReachedException;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitHubApiPort;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpGitHubApiAdapter implements GitHubApiPort {

  private static final String ACCEPT_JSON = "application/vnd.github+json";
  private static final String ACCEPT_RAW = "application/vnd.github.raw+json";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String token;
  private final Duration requestTimeout;

  public HttpGitHubApiAdapter(
      AnalysisProperties properties,
      ObjectMapper objectMapper,
      @Value("${huocode.github-base-url:https://api.github.com}") String baseUrl) {
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(properties.getGithubRequestTimeout()).build();
    this.objectMapper = objectMapper;
    this.baseUrl = baseUrl;
    this.token = properties.getGithubToken();
    this.requestTimeout = properties.getGithubRequestTimeout();
  }

  @Override
  public boolean repoExists(RepoUrl repoUrl) {
    try {
      getOrThrow(repoUrl, repoPath(repoUrl), false);
      return true;
    } catch (RepoNotFoundException e) {
      return false;
    }
  }

  @Override
  public String defaultBranch(RepoUrl repoUrl) {
    return json(repoUrl, repoPath(repoUrl)).get("default_branch").asText();
  }

  @Override
  public String latestCommitSha(RepoUrl repoUrl) {
    String branch = defaultBranch(repoUrl);
    return json(repoUrl, repoPath(repoUrl) + "/commits/" + encode(branch) + "?per_page=1")
        .get("sha")
        .asText();
  }

  @Override
  public List<String> listAllPaths(RepoUrl repoUrl, String commitSha) {
    List<String> paths = new ArrayList<>();
    for (JsonNode entry : tree(repoUrl, commitSha)) {
      if ("blob".equals(entry.get("type").asText())) {
        paths.add(entry.get("path").asText());
      }
    }
    return paths;
  }

  @Override
  public long fileCount(RepoUrl repoUrl, String commitSha) {
    long count = 0;
    for (JsonNode entry : tree(repoUrl, commitSha)) {
      if ("blob".equals(entry.get("type").asText())) {
        count++;
      }
    }
    return count;
  }

  @Override
  public String rawContent(RepoUrl repoUrl, String path, String commitSha) {
    String urlPath =
        repoPath(repoUrl) + "/contents/" + encodePath(path) + "?ref=" + encode(commitSha);
    return getOrThrow(repoUrl, urlPath, true).body();
  }

  @SneakyThrows
  private JsonNode json(RepoUrl repoUrl, String path) {
    return objectMapper.readTree(getOrThrow(repoUrl, path, false).body());
  }

  private JsonNode tree(RepoUrl repoUrl, String commitSha) {
    return json(repoUrl, repoPath(repoUrl) + "/git/trees/" + encode(commitSha) + "?recursive=1")
        .get("tree");
  }

  private HttpResponse<String> getOrThrow(RepoUrl repoUrl, String path, boolean raw) {
    HttpResponse<String> response;
    try {
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(requestTimeout)
              .header("Accept", raw ? ACCEPT_RAW : ACCEPT_JSON)
              .header("User-Agent", "huocode")
              .header("X-GitHub-Api-Version", "2022-11-28")
              .GET();
      if (token != null && !token.isBlank()) {
        requestBuilder.header("Authorization", "Bearer " + token);
      }
      response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(
          "GitHub request failed for " + repoUrl + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "GitHub request interrupted for " + repoUrl + ": " + e.getMessage(), e);
    }
    if (response.statusCode() == 404) {
      throw new RepoNotFoundException("repo " + repoUrl + " not found or private");
    }
    if (response.statusCode() == 403 && isRateLimited(response)) {
      throw new GitHubRateLimitReachedException("GitHub API rate limit reached");
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "GitHub API returned "
              + response.statusCode()
              + " for "
              + repoUrl
              + ": "
              + response.body());
    }
    return response;
  }

  private boolean isRateLimited(HttpResponse<String> response) {
    return "0".equals(response.headers().firstValue("x-ratelimit-remaining").orElse(null))
        || response.body().contains("API rate limit exceeded");
  }

  private String repoPath(RepoUrl repoUrl) {
    return "/repos/" + repoUrl.owner() + "/" + repoUrl.repo();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String encodePath(String path) {
    return java.util.Arrays.stream(path.split("/"))
        .map(HttpGitHubApiAdapter::encode)
        .collect(Collectors.joining("/"));
  }
}
