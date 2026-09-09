package io.huocode.api.model;

public record RepoUrl(String owner, String repo) {

  public String canonicalUrl() {
    return "https://github.com/" + owner + "/" + repo;
  }

  @Override
  public String toString() {
    return owner + "/" + repo;
  }
}
