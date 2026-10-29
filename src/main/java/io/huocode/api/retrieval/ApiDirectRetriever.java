package io.huocode.api.retrieval;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.language.LanguageDetector;
import io.huocode.api.model.RepoChurn;
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
    List<String> javaFiles =
        files.stream().filter(file -> LanguageDetector.isJava(Path.of(file))).toList();
    Map<String, String> contents = new ConcurrentHashMap<>();
    int threads = Math.max(1, Math.min(MAX_CONCURRENT_FETCHES, javaFiles.size()));
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      List<CompletableFuture<Void>> futures =
          javaFiles.stream()
              .map(
                  file ->
                      CompletableFuture.runAsync(
                          () -> contents.put(file, gitHubApiPort.rawContent(repoUrl, file, sha)),
                          executor))
              .toList();
      join(futures);
    } finally {
      executor.shutdown();
    }
    RepoChurn churn = gitHubApiPort.repoChurn(repoUrl, sha, properties.getCommitWindow());
    return new ApiDirectRetrieverData(sha, files, contents, churn.churnByPath(), churn.window());
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
