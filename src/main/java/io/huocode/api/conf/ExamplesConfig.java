package io.huocode.api.conf;

import io.huocode.api.endpoint.rest.model.GetExampleRepos200ResponseInner;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExamplesConfig {

  private final List<GetExampleRepos200ResponseInner> examples;

  public ExamplesConfig(@Value("${huocode.examples:}") String examplesCsv) {
    this.examples =
        Arrays.stream(examplesCsv.split(","))
            .map(String::trim)
            .filter(repo -> !repo.isBlank())
            .map(ExamplesConfig::toExample)
            .toList();
  }

  @Bean
  public List<GetExampleRepos200ResponseInner> exampleRepos() {
    return examples;
  }

  static GetExampleRepos200ResponseInner toExample(String repo) {
    return new GetExampleRepos200ResponseInner()
        .repo(repo)
        .repoUrl(URI.create("https://github.com/" + repo));
  }
}
