package io.huocode.api.adapter.git;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.model.RepoUrl;
import java.nio.file.Path;

public class FileUrlScmAdapter extends JGitScmAdapter {

  private final Path source;

  public FileUrlScmAdapter(AnalysisProperties properties, Path source) {
    super(properties);
    this.source = source;
  }

  @Override
  protected String cloneUrl(RepoUrl repoUrl) {
    return source.toUri().toString();
  }
}
