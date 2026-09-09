package io.huocode.api.adapter.github;

import static io.huocode.api.utils.ChurnParser.isBot;

import com.fasterxml.jackson.databind.JsonNode;
import io.huocode.api.model.RepoUrl;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CommitReader {

  private static final int PAGE_SIZE = 100;

  private CommitReader() {}

  public static List<JsonNode> fetchCommits(
      GitHubApiHttp http, RepoUrl repoUrl, String path, int maxCommits) {
    List<JsonNode> commits = new ArrayList<>();
    int page = 1;
    while (commits.size() < maxCommits) {
      JsonNode items = http.getJson(repoUrl, commitsUrl(http, repoUrl, path, page));
      if (items.isEmpty()) {
        break;
      }
      for (JsonNode item : items) {
        commits.add(item);
        if (commits.size() >= maxCommits) {
          break;
        }
      }
      if (items.size() < PAGE_SIZE) {
        break;
      }
      page++;
    }
    return commits;
  }

  public static boolean isBotAuthor(JsonNode item) {
    String login = item.path("author").path("login").asText("");
    if (isBot(login)) {
      return true;
    }
    return isBot(item.path("commit").path("author").path("name").asText(""));
  }

  public static String authorOf(JsonNode item) {
    JsonNode author = item.get("author");
    if (author != null && author.hasNonNull("login")) {
      return author.get("login").asText();
    }
    return item.path("commit").path("author").path("name").asText("");
  }

  public static Optional<Instant> dateOf(JsonNode item) {
    String date = item.path("commit").path("committer").path("date").asText(null);
    if (date == null) {
      date = item.path("commit").path("author").path("date").asText(null);
    }
    if (date == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(OffsetDateTime.parse(date).toInstant());
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String commitsUrl(GitHubApiHttp http, RepoUrl repoUrl, String path, int page) {
    String query =
        path == null
            ? "per_page=" + PAGE_SIZE + "&page=" + page
            : "path=" + GitHubApiHttp.encodePath(path) + "&per_page=" + PAGE_SIZE + "&page=" + page;
    return http.repoPath(repoUrl) + "/commits?" + query;
  }
}
