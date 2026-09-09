package io.huocode.api.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.huocode.api.TestGitRepos;
import io.huocode.api.adapter.git.FileUrlGitCliAdapter;
import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoUrl;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloneRetrieverTest {

  private static final AnalysisProperties PROPERTIES =
      new AnalysisProperties(
          "",
          Duration.ofSeconds(5),
          300,
          20000,
          15,
          500,
          10,
          Duration.ofSeconds(10),
          1048576,
          Duration.ofHours(48),
          60,
          Duration.ofMinutes(15),
          10,
          60,
          2);

  @TempDir Path temp;

  @Test
  void retrieve_clones_and_aggregates_data() throws IOException {
    Path source = temp.resolve("source");
    TestGitRepos.init(source);
    TestGitRepos.write(source, "A.java", "class A {}\n");
    TestGitRepos.write(source, "B.java", "class B {}\n");
    TestGitRepos.commit(source, "first commit");
    TestGitRepos.write(source, "A.java", "class A { int x; }\n");
    TestGitRepos.commit(source, "second commit");

    CloneRetriever retriever = new CloneRetriever(new FileUrlGitCliAdapter(PROPERTIES, source));
    CloneRetrieverData data =
        retriever.retrieve(new RepoUrl("owner", "repo"), temp.resolve("work"));

    assertEquals(40, data.sha().length());
    assertEquals(List.of("A.java", "B.java"), data.files());
    assertEquals(new Churn(2, 1), data.churn().churnByPath().get("A.java"));
    assertEquals(new Churn(1, 1), data.churn().churnByPath().get("B.java"));
    assertEquals(2, data.churn().window().commitsAnalyzed());
  }
}
