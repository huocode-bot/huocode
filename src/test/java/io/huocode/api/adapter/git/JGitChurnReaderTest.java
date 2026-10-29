package io.huocode.api.adapter.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.huocode.api.TestGitRepos;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoChurn;
import io.huocode.api.utils.ChurnParser;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JGitChurnReaderTest {

  @TempDir Path temp;

  @Test
  void read_skips_the_shallow_clone_boundary_commit() throws IOException {
    Path source = temp.resolve("source");
    TestGitRepos.init(source);
    TestGitRepos.write(source, "A.java", "class A {\n  int a;\n  int b;\n}\n");
    TestGitRepos.commit(source, "first");
    TestGitRepos.write(source, "A.java", "class A {\n  int a2;\n  int b;\n}\n");
    TestGitRepos.commit(source, "second");
    TestGitRepos.write(source, "A.java", "class A {\n  int a2;\n  int b2;\n}\n");
    TestGitRepos.commit(source, "third");
    Path shallow = temp.resolve("shallow");
    TestGitRepos.run(
        temp, "clone", "-q", "--depth", "2", source.toUri().toString(), shallow.toString());

    RepoChurn churn = ChurnParser.aggregate(new JGitChurnReader(500).read(shallow));

    // "second" is the boundary of the depth-2 clone: its parent is missing, so it cannot be
    // diffed and must not be counted as adding the whole file.
    assertEquals(1, churn.window().commitsAnalyzed());
    assertEquals(new Churn(1, 1, 1, 1), churn.churnByPath().get("A.java"));
  }
}
