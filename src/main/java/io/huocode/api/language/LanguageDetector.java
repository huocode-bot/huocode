package io.huocode.api.language;

import java.nio.file.Path;
import java.util.Map;

public final class LanguageDetector {

  private static final Map<String, String> EXTENSIONS =
      Map.ofEntries(
          Map.entry("java", "java"),
          Map.entry("py", "python"),
          Map.entry("js", "javascript"),
          Map.entry("jsx", "javascript"),
          Map.entry("mjs", "javascript"),
          Map.entry("ts", "typescript"),
          Map.entry("tsx", "typescript"),
          Map.entry("go", "go"),
          Map.entry("rs", "rust"),
          Map.entry("rb", "ruby"),
          Map.entry("php", "php"),
          Map.entry("c", "c"),
          Map.entry("h", "c"),
          Map.entry("cpp", "cpp"),
          Map.entry("cc", "cpp"),
          Map.entry("cxx", "cpp"),
          Map.entry("hpp", "cpp"),
          Map.entry("hh", "cpp"),
          Map.entry("cs", "csharp"),
          Map.entry("kt", "kotlin"),
          Map.entry("kts", "kotlin"),
          Map.entry("scala", "scala"),
          Map.entry("swift", "swift"),
          Map.entry("m", "objective-c"),
          Map.entry("html", "html"),
          Map.entry("htm", "html"),
          Map.entry("css", "css"),
          Map.entry("scss", "scss"),
          Map.entry("less", "less"),
          Map.entry("json", "json"),
          Map.entry("yaml", "yaml"),
          Map.entry("yml", "yaml"),
          Map.entry("xml", "xml"),
          Map.entry("md", "markdown"),
          Map.entry("markdown", "markdown"),
          Map.entry("sql", "sql"),
          Map.entry("sh", "shell"),
          Map.entry("bash", "shell"),
          Map.entry("bat", "batch"),
          Map.entry("cmd", "batch"),
          Map.entry("properties", "properties"),
          Map.entry("gradle", "gradle"),
          Map.entry("tf", "terraform"),
          Map.entry("toml", "toml"),
          Map.entry("ini", "ini"),
          Map.entry("txt", "text"),
          Map.entry("csv", "csv"),
          Map.entry("dart", "dart"),
          Map.entry("ex", "elixir"),
          Map.entry("exs", "elixir"),
          Map.entry("lua", "lua"),
          Map.entry("pl", "perl"),
          Map.entry("pm", "perl"),
          Map.entry("r", "r"),
          Map.entry("vue", "vue"),
          Map.entry("svelte", "svelte"));

  private LanguageDetector() {}

  public static boolean isJava(Path path) {
    return "java".equals(languageOf(path));
  }

  public static String languageOf(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    if (dot < 0) {
      return "unknown";
    }
    return EXTENSIONS.getOrDefault(name.substring(dot + 1).toLowerCase(), "unknown");
  }
}
