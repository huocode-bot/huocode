package io.huocode.api.retrieval;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.language.LanguageDetector;
import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitHubApiPort;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiDirectRetriever {

  private static final int MAX_CONCURRENT_FETCHES = 8;

  private final GitHubApiPort gitHubApiPort;
  private final AnalysisProperties properties;

  public ApiDirectRetrieverData retrieve(RepoUrl repoUrl, String sha) {
    List<String> files = gitHubApiPort.listAllPaths(repoUrl, sha);
    int commitWindow = properties.getCommitWindow();
    Map<String, String> contents = new ConcurrentHashMap<>();
    Map<String, Churn> churnByPath = new ConcurrentHashMap<>();
    int threads = Math.max(1, Math.min(MAX_CONCURRENT_FETCHES, files.size()));
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      List<CompletableFuture<Void>> futures =
          files.stream()
              .map(
                  file ->
                      CompletableFuture.runAsync(
                          () -> fetchFor(repoUrl, sha, commitWindow, file, contents, churnByPath),
                          executor))
              .toList();
      join(futures);
    } finally {
      executor.shutdown();
    }
    AnalysisWindowData window = gitHubApiPort.analysisWindow(repoUrl, commitWindow);
    return new ApiDirectRetrieverData(sha, files, contents, churnByPath, window);
  }

  private void fetchFor(
      RepoUrl repoUrl,
      String sha,
      int commitWindow,
      String file,
      Map<String, String> contents,
      Map<String, Churn> churnByPath) {
    churnByPath.put(file, gitHubApiPort.churnForPath(repoUrl, file, commitWindow));
    if (LanguageDetector.isJava(Path.of(file))) {
      contents.put(file, gitHubApiPort.rawContent(repoUrl, file, sha));
    }
  }

  private static void join(List<CompletableFuture<Void>> futures) {
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException(cause);
    }
  }
}
