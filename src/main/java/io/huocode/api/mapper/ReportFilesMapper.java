package io.huocode.api.mapper;

import static java.util.Comparator.comparing;
import static java.util.Comparator.nullsLast;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.FileResult;
import io.huocode.api.endpoint.rest.model.FileStatus;
import io.huocode.api.endpoint.rest.model.ListReportFiles200Response;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ReportFilesMapper {

  private ReportFilesMapper() {}

  public static ListReportFiles200Response slice(
      AnalysisResult report, int page, int pageSize, String sort, FileStatus status) {
    List<FileResult> filtered = new ArrayList<>();
    List<FileResult> all = report.getFiles() == null ? List.of() : report.getFiles();
    for (FileResult file : all) {
      if (status == null || status == file.getStatus()) {
        filtered.add(file);
      }
    }
    filtered.sort(comparator(sort));
    int from = (page - 1) * pageSize;
    int to = Math.min(from + pageSize, filtered.size());
    List<FileResult> files = from >= filtered.size() ? List.of() : filtered.subList(from, to);
    boolean hasMore = page * pageSize < filtered.size();
    return new ListReportFiles200Response()
        .files(files)
        .page(page)
        .pageSize(pageSize)
        .totalFiles(filtered.size())
        .hasMore(hasMore);
  }

  private static Comparator<FileResult> comparator(String sort) {
    if ("path".equals(sort)) {
      return comparing(FileResult::getPath);
    }
    return comparing(FileResult::getCodeHealthScore, nullsLast(Comparator.naturalOrder()))
        .thenComparing(FileResult::getPath);
  }
}
