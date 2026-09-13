package io.huocode.api.adapter.pmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.model.ComplexityResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PmdAdapterTest {

  private final PmdAdapter adapter = new PmdAdapter();

  @TempDir Path temp;

  @Test
  void analyze_keeps_worst_method_complexity_per_file() throws Exception {
    Files.writeString(
        temp.resolve("Foo.java"),
        "package sample;\n"
            + "class Foo {\n"
            + "  void simple() {}\n"
            + "  int branchy(int a) { if (a > 0) { return 1; } else { return 2; } }\n"
            + "  int loops(int n) {\n"
            + "    int c = 0;\n"
            + "    for (int i = 0; i < n; i++) { while (c < n) { c++; } if (c % 2 == 0) c--; }\n"
            + "    return c;\n"
            + "  }\n"
            + "}\n");

    // Per-method CC: simple=1, branchy=2, loops=4 (for + while + if). We keep the worst method
    // (4), not the sum (7), to match the industry per-function complexity convention.
    ComplexityResult result = adapter.analyze(temp, List.of("Foo.java"));

    assertEquals(Map.of("Foo.java", 4), result.complexityByPath());
    assertTrue(result.parseErrorPaths().isEmpty());
  }

  @Test
  void analyze_reports_unparseable_files() throws Exception {
    Files.writeString(temp.resolve("Broken.java"), "class Broken { void m() { return\n");

    ComplexityResult result = adapter.analyze(temp, List.of("Broken.java"));

    assertTrue(result.complexityByPath().isEmpty());
    assertEquals(List.of("Broken.java"), result.parseErrorPaths());
  }

  @Test
  void analyze_returns_empty_result_for_no_files() {
    ComplexityResult result = adapter.analyze(temp, List.of());

    assertTrue(result.complexityByPath().isEmpty());
    assertTrue(result.parseErrorPaths().isEmpty());
  }

  @Test
  void analyze_handles_empty_class_without_violations() throws Exception {
    Files.writeString(temp.resolve("Empty.java"), "class Empty {}\n");

    ComplexityResult result = adapter.analyze(temp, List.of("Empty.java"));

    assertTrue(result.complexityByPath().isEmpty());
    assertTrue(result.parseErrorPaths().isEmpty());
  }
}
