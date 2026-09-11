package io.huocode.api.endpoint.rest.controller;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.FileStatus;
import io.huocode.api.endpoint.rest.model.GetExampleRepos200ResponseInner;
import io.huocode.api.endpoint.rest.model.ListReportFiles200Response;
import io.huocode.api.mapper.ReportFilesMapper;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.service.RepoUrlAnalyzerService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReportController {

  private final RepoUrlAnalyzerService analyzerService;
  private final List<GetExampleRepos200ResponseInner> exampleRepos;

  @GetMapping("/report/{owner}/{repo}")
  public AnalysisResult getPublicReport(@PathVariable String owner, @PathVariable String repo) {
    return analyzerService.getLatestReport(new RepoUrl(owner, repo));
  }

  @GetMapping("/report/{owner}/{repo}/files")
  public ListReportFiles200Response listReportFiles(
      @PathVariable String owner,
      @PathVariable String repo,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize,
      @RequestParam(defaultValue = "risk") String sort,
      @RequestParam(required = false) String status) {
    AnalysisResult report = analyzerService.getLatestReport(new RepoUrl(owner, repo));
    FileStatus fileStatus = status == null ? null : FileStatus.fromValue(status);
    return ReportFilesMapper.slice(
        report, Math.max(1, page), Math.min(Math.max(1, pageSize), 500), sort, fileStatus);
  }

  @GetMapping("/examples")
  public List<GetExampleRepos200ResponseInner> getExampleRepos() {
    return exampleRepos;
  }
}
