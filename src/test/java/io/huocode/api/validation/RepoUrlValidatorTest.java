package io.huocode.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.huocode.api.exception.RepoUrlValidationException;
import io.huocode.api.model.RepoUrl;
import java.net.URI;
import org.junit.jupiter.api.Test;

class RepoUrlValidatorTest {

  private final RepoUrlValidator validator = new RepoUrlValidator();

  @Test
  void accepts_valid_github_urls() {
    assertEquals(
        new RepoUrl("owner", "repo"),
        validator.validate(URI.create("https://github.com/owner/repo")));
    assertEquals(
        new RepoUrl("My-Org", "my_repo.v2"),
        validator.validate(URI.create("https://github.com/My-Org/my_repo.v2")));
    assertEquals(
        new RepoUrl("a-b", "c-d"), validator.validate(URI.create("https://github.com/a-b/c-d")));
  }

  @Test
  void rejects_non_https_or_non_github_urls() {
    assertThrows(RepoUrlValidationException.class, () -> validator.validate(null));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("ssh://git@github.com/owner/repo")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("http://github.com/owner/repo")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://gitlab.com/owner/repo")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://www.github.com/owner/repo")));
  }

  @Test
  void rejects_malformed_paths() {
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://github.com/owner")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://github.com/owner/repo/")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://github.com/owner/repo/extra")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://github.com/owner/repo.git")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://github.com//repo")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://github.com/owner/..repo")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://github.com/owner/repo?x=1")));
    assertThrows(
        RepoUrlValidationException.class,
        () -> validator.validate(URI.create("https://github.com/owner/repo#frag")));
  }

  @Test
  void canonical_url_roundtrips() {
    assertEquals("https://github.com/owner/repo", new RepoUrl("owner", "repo").canonicalUrl());
  }
}
