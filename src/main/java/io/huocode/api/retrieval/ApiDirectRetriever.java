package io.huocode.api.retrieval;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitHubApiPort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiDirectRetriever {

  private final GitHubApiPort gitHubApiPort;
  private final AnalysisProperties properties;

  public ApiDirectRetrieverData retrieve(RepoUrl repoUrl) {
    String sha = gitHubApiPort.latestCommitSha(repoUrl);
    List<String> files = gitHubApiPort.listAllPaths(repoUrl, sha);
    int commitWindow = properties.getCommitWindow();
    Map<String, String> contents = new HashMap<>();
    Map<String, Churn> churnByPath = new HashMap<>();
    for (String file : files) {
      contents.put(file, gitHubApiPort.rawContent(repoUrl, file, sha));
      churnByPath.put(file, gitHubApiPort.churnForPath(repoUrl, file, commitWindow));
    }
    AnalysisWindowData window = gitHubApiPort.analysisWindow(repoUrl, commitWindow);
    return new ApiDirectRetrieverData(sha, files, contents, churnByPath, window);
  }
}
