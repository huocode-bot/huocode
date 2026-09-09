package io.huocode.api.adapter.github;

import static java.util.Arrays.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.exception.GitHubRateLimitReachedException;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.model.RepoUrl;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GitHubApiHttp {

  private static final String ACCEPT_JSON = "application/vnd.github+json";
  private static final String ACCEPT_RAW = "application/vnd.github.raw+json";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String token;
  private final Duration requestTimeout;

  public GitHubApiHttp(
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

  public String repoPath(RepoUrl repoUrl) {
    return "/repos/" + repoUrl.owner() + "/" + repoUrl.repo();
  }

  @SneakyThrows
  public JsonNode getJson(RepoUrl repoUrl, String path) {
    return objectMapper.readTree(getOrThrow(repoUrl, path, false).body());
  }

  public String getRaw(RepoUrl repoUrl, String path) {
    return getOrThrow(repoUrl, path, true).body();
  }

  public static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public static String encodePath(String path) {
    return stream(path.split("/")).map(GitHubApiHttp::encode).collect(Collectors.joining("/"));
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

  private static boolean isRateLimited(HttpResponse<String> response) {
    return "0".equals(response.headers().firstValue("x-ratelimit-remaining").orElse(null))
        || response.body().contains("API rate limit exceeded");
  }
}
