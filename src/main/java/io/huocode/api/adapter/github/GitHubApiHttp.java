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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GitHubApiHttp {

  private static final String ACCEPT_JSON = "application/vnd.github+json";
  private static final String ACCEPT_RAW = "application/vnd.github.raw+json";
  private static final int MAX_ATTEMPTS = 3;
  private static final long MAX_RETRY_DELAY_SECONDS = 60;

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
    for (int attempt = 1; ; attempt++) {
      HttpResponse<String> response;
      try {
        response = send(path, raw);
      } catch (HttpTimeoutException e) {
        // Already waited a full request timeout: retrying would multiply it.
        throw new IllegalStateException(
            "GitHub request timed out for " + repoUrl + ": " + e.getMessage(), e);
      } catch (IOException e) {
        if (attempt < MAX_ATTEMPTS) {
          pause(backoff(attempt));
          continue;
        }
        throw new IllegalStateException(
            "GitHub request failed for " + repoUrl + ": " + e.getMessage(), e);
      }
      if (response.statusCode() == 200) {
        return response;
      }
      if (response.statusCode() == 404) {
        throw new RepoNotFoundException("repo " + repoUrl + " not found or private");
      }
      // The hourly quota only comes back at its reset: retrying now would not help.
      if (isRateLimited(response)) {
        throw new GitHubRateLimitReachedException("GitHub API rate limit reached");
      }
      if (isTransient(response) && attempt < MAX_ATTEMPTS) {
        pause(retryDelay(response, attempt));
        continue;
      }
      throw new IllegalStateException(
          "GitHub API returned "
              + response.statusCode()
              + " for "
              + repoUrl
              + ": "
              + response.body());
    }
  }

  private HttpResponse<String> send(String path, boolean raw) throws IOException {
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
    try {
      return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub request interrupted: " + e.getMessage(), e);
    }
  }

  /** Server errors and GitHub's secondary (abuse) rate limit pass after a wait. */
  private static boolean isTransient(HttpResponse<String> response) {
    int status = response.statusCode();
    return status >= 500
        || status == 429
        || (status == 403
            && (response.body().contains("secondary rate limit")
                || response.body().contains("abuse detection")));
  }

  private static Duration retryDelay(HttpResponse<String> response, int attempt) {
    try {
      OptionalLong seconds = response.headers().firstValueAsLong("retry-after");
      if (seconds.isPresent()) {
        return Duration.ofSeconds(Math.clamp(seconds.getAsLong(), 0, MAX_RETRY_DELAY_SECONDS));
      }
    } catch (NumberFormatException e) {
      // Retry-After given as an HTTP date: use the defaults below.
    }
    // GitHub asks to wait at least a minute after a secondary rate limit without Retry-After.
    return response.statusCode() >= 500
        ? backoff(attempt)
        : Duration.ofSeconds(MAX_RETRY_DELAY_SECONDS);
  }

  private static Duration backoff(int attempt) {
    return Duration.ofSeconds(1L << (attempt - 1));
  }

  private static void pause(Duration delay) {
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub retry interrupted", e);
    }
  }

  private static boolean isRateLimited(HttpResponse<String> response) {
    int status = response.statusCode();
    return (status == 403 || status == 429)
        && ("0".equals(response.headers().firstValue("x-ratelimit-remaining").orElse(null))
            || response.body().contains("API rate limit exceeded"));
  }
}
