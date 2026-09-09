package io.huocode.api.aggregation;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.language.LanguageDetector;
import io.huocode.api.mapper.AnalysisResultMapper;
import io.huocode.api.model.Churn;
import io.huocode.api.model.ComplexityResult;
import io.huocode.api.model.FileErrorKind;
import io.huocode.api.model.FileMeasurement;
import io.huocode.api.model.FileStatusKind;
import io.huocode.api.model.RepoScore;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.model.RetrievalStrategy;
import io.huocode.api.port.ComplexityPort;
import io.huocode.api.port.GitHubApiPort;
import io.huocode.api.retrieval.ApiDirectRetriever;
import io.huocode.api.retrieval.ApiDirectRetrieverData;
import io.huocode.api.retrieval.CloneRetriever;
import io.huocode.api.retrieval.CloneRetrieverData;
import io.huocode.api.retrieval.RetrievalStrategySelector;
import io.huocode.api.scoring.ScoreEngine;
import io.huocode.api.utils.FilesUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RepoAggregator {

  private static final String CLONE_DIRECTORY = "clone";
  private static final String WORK_DIRECTORY = "repo";

  private final RetrievalStrategySelector strategySelector;
  private final CloneRetriever cloneRetriever;
  private final ApiDirectRetriever apiDirectRetriever;
  private final GitHubApiPort gitHubApiPort;
  private final ComplexityPort complexityPort;
  private final ScoreEngine scoreEngine;
  private final AnalysisProperties properties;

  public AnalysisResult analyze(RepoUrl repoUrl) throws IOException {
    String sha = gitHubApiPort.latestCommitSha(repoUrl);
    RetrievalStrategy strategy = strategySelector.select(gitHubApiPort.fileCount(repoUrl, sha));
    return switch (strategy) {
      case API_DIRECT -> analyzeApiDirect(repoUrl);
      case CLONE -> analyzeClone(repoUrl);
    };
  }

  private AnalysisResult analyzeClone(RepoUrl repoUrl) throws IOException {
    Path work = Files.createTempDirectory("huocode-work-");
    try {
      CloneRetrieverData data = cloneRetriever.retrieve(repoUrl, work);
      Path repoDirectory = work.resolve(CLONE_DIRECTORY);
      List<FileMeasurement> measurements =
          measure(data.files(), data.churn().churnByPath(), repoDirectory);
      RepoScore score = scoreEngine.score(measurements);
      return AnalysisResultMapper.toAnalysisResult(
          repoUrl,
          data.sha(),
          RetrievalStrategy.CLONE,
          data.churn().window(),
          Instant.now(),
          score,
          measurements,
          properties);
    } finally {
      FilesUtils.deleteRecursively(work);
    }
  }

  private AnalysisResult analyzeApiDirect(RepoUrl repoUrl) throws IOException {
    ApiDirectRetrieverData data = apiDirectRetriever.retrieve(repoUrl);
    Path work = Files.createTempDirectory("huocode-work-");
    try {
      Path repoDirectory = work.resolve(WORK_DIRECTORY);
      materialize(data.contents(), repoDirectory);
      List<FileMeasurement> measurements = measure(data.files(), data.churnByPath(), repoDirectory);
      RepoScore score = scoreEngine.score(measurements);
      return AnalysisResultMapper.toAnalysisResult(
          repoUrl,
          data.sha(),
          RetrievalStrategy.API_DIRECT,
          data.window(),
          Instant.now(),
          score,
          measurements,
          properties);
    } finally {
      FilesUtils.deleteRecursively(work);
    }
  }

  private static void materialize(Map<String, String> contents, Path repoDirectory)
      throws IOException {
    for (Map.Entry<String, String> entry : contents.entrySet()) {
      Path target = repoDirectory.resolve(entry.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, entry.getValue());
    }
  }

  private List<FileMeasurement> measure(
      List<String> files, Map<String, Churn> churnByPath, Path repoDirectory) throws IOException {
    List<String> javaFiles = new ArrayList<>();
    for (String file : files) {
      Path path = Path.of(file);
      if (LanguageDetector.isJava(path)
          && Files.size(repoDirectory.resolve(file)) <= properties.getMaxFileSizeBytes()) {
        javaFiles.add(file);
      }
    }
    ComplexityResult complexity = complexityPort.analyze(repoDirectory, javaFiles);
    Set<String> parseErrors = new HashSet<>(complexity.parseErrorPaths());
    List<FileMeasurement> measurements = new ArrayList<>(files.size());
    for (String file : files) {
      Path path = Path.of(file);
      Churn churn = churnByPath.getOrDefault(file, Churn.ZERO);
      if (parseErrors.contains(file)) {
        measurements.add(
            new FileMeasurement(
                file, FileStatusKind.ERROR, null, FileErrorKind.PARSE_ERROR, 0, churn));
      } else if (!LanguageDetector.isJava(path)) {
        measurements.add(
            new FileMeasurement(
                file,
                FileStatusKind.UNSUPPORTED_LANGUAGE,
                LanguageDetector.languageOf(path),
                null,
                0,
                churn));
      } else if (Files.size(repoDirectory.resolve(file)) > properties.getMaxFileSizeBytes()) {
        measurements.add(
            new FileMeasurement(
                file, FileStatusKind.ERROR, null, FileErrorKind.FILE_TOO_LARGE, 0, churn));
      } else {
        measurements.add(
            new FileMeasurement(
                file,
                FileStatusKind.ANALYZED,
                null,
                null,
                complexity.complexityByPath().getOrDefault(file, 0),
                churn));
      }
    }
    return measurements;
  }
}
