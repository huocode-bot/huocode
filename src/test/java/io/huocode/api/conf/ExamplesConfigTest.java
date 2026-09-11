package io.huocode.api.conf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.endpoint.rest.model.GetExampleRepos200ResponseInner;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExamplesConfigTest {

  @Test
  void parses_csv_into_example_entries() {
    ExamplesConfig config = new ExamplesConfig("foo/bar,  baz/qux ,foo/baz");

    List<String> repos =
        config.exampleRepos().stream().map(GetExampleRepos200ResponseInner::getRepo).toList();
    assertEquals(List.of("foo/bar", "baz/qux", "foo/baz"), repos);
    assertEquals(
        "https://github.com/foo/bar", config.exampleRepos().get(0).getRepoUrl().toString());
  }

  @Test
  void blank_env_var_yields_no_examples() {
    assertTrue(new ExamplesConfig("").exampleRepos().isEmpty());
    assertTrue(new ExamplesConfig("  ,  ").exampleRepos().isEmpty());
  }
}
