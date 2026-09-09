package io.huocode.api.validation;

import io.huocode.api.exception.RepoUrlValidationException;
import io.huocode.api.model.RepoUrl;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RepoUrlValidator {

  private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");
  private static final Pattern REPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  public RepoUrl validate(URI repoUrl) {
    if (repoUrl == null
        || !"https".equalsIgnoreCase(repoUrl.getScheme())
        || !"github.com".equalsIgnoreCase(repoUrl.getHost())
        || repoUrl.getRawUserInfo() != null
        || repoUrl.getQuery() != null
        || repoUrl.getFragment() != null) {
      throw invalid();
    }
    String rawPath = repoUrl.getRawPath();
    String[] segments = rawPath == null ? new String[0] : rawPath.split("/", -1);
    if (segments.length != 3 || !segments[0].isEmpty()) {
      throw invalid();
    }
    String owner = segments[1];
    String repo = segments[2];
    if (!OWNER.matcher(owner).matches() || !REPO.matcher(repo).matches()) {
      throw invalid();
    }
    if (repo.endsWith(".git")
        || repo.startsWith(".")
        || repo.endsWith(".")
        || repo.contains("..")) {
      throw invalid();
    }
    return new RepoUrl(owner, repo);
  }

  private RepoUrlValidationException invalid() {
    return new RepoUrlValidationException(
        "repoUrl must strictly match https://github.com/{owner}/{repo}");
  }
}
