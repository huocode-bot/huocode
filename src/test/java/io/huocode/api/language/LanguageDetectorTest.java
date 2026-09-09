package io.huocode.api.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LanguageDetectorTest {

  @Test
  void detects_common_languages_by_extension() {
    assertEquals("java", LanguageDetector.languageOf(Path.of("src/A.java")));
    assertEquals("python", LanguageDetector.languageOf(Path.of("main.py")));
    assertEquals("python", LanguageDetector.languageOf(Path.of("script.PY")));
    assertEquals("typescript", LanguageDetector.languageOf(Path.of("app.ts")));
    assertEquals("javascript", LanguageDetector.languageOf(Path.of("app.js")));
    assertEquals("go", LanguageDetector.languageOf(Path.of("main.go")));
    assertEquals("rust", LanguageDetector.languageOf(Path.of("lib.rs")));
    assertEquals("cpp", LanguageDetector.languageOf(Path.of("src/util.cpp")));
    assertEquals("csharp", LanguageDetector.languageOf(Path.of("Program.cs")));
    assertEquals("kotlin", LanguageDetector.languageOf(Path.of("Main.kt")));
    assertEquals("markdown", LanguageDetector.languageOf(Path.of("README.md")));
    assertEquals("yaml", LanguageDetector.languageOf(Path.of("conf.yaml")));
  }

  @Test
  void is_java_only_for_java_files() {
    assertTrue(LanguageDetector.isJava(Path.of("A.java")));
    assertFalse(LanguageDetector.isJava(Path.of("A.py")));
  }

  @Test
  void unknown_for_missing_or_unmapped_extension() {
    assertEquals("unknown", LanguageDetector.languageOf(Path.of("Makefile")));
    assertEquals("unknown", LanguageDetector.languageOf(Path.of("data.xyz")));
  }
}
