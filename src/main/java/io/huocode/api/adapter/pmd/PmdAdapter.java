package io.huocode.api.adapter.pmd;

import io.huocode.api.model.ComplexityResult;
import io.huocode.api.port.ComplexityPort;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import org.springframework.stereotype.Component;

@Component
public class PmdAdapter implements ComplexityPort {

  private static final Pattern COMPLEXITY_PATTERN =
      Pattern.compile("cyclomatic complexity of (\\d+)");

  @Override
  public ComplexityResult analyze(Path repoDirectory, Collection<String> relativePaths) {
    if (relativePaths.isEmpty()) {
      return new ComplexityResult(Map.of(), List.of());
    }
    PMDConfiguration config = new PMDConfiguration();
    config.setDefaultLanguageVersion(LanguageRegistry.PMD.getLanguageVersionById("java", "21"));
    Map<String, Integer> complexityByPath = new HashMap<>();
    List<String> parseErrorPaths = new ArrayList<>();
    try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
      for (String path : relativePaths) {
        pmd.files().addFile(repoDirectory.resolve(path));
      }
      pmd.addRuleSet(pmd.newRuleSetLoader().loadFromResource("pmd/cyclo-java.xml"));
      Report report = pmd.performAnalysisAndCollectReport();
      Path repo = repoDirectory.toAbsolutePath().normalize();
      for (RuleViolation violation : report.getViolations()) {
        int complexity = complexityOf(violation.getDescription());
        if (complexity > 0) {
          String path = relativePath(repo, violation.getFileId());
          complexityByPath.merge(path, complexity, Integer::sum);
        }
      }
      for (Report.ProcessingError error : report.getProcessingErrors()) {
        parseErrorPaths.add(relativePath(repo, error.getFileId()));
      }
    }
    return new ComplexityResult(complexityByPath, parseErrorPaths);
  }

  private static int complexityOf(String description) {
    Matcher matcher = COMPLEXITY_PATTERN.matcher(description);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  private static String relativePath(Path repoDirectory, FileId fileId) {
    Path file = Path.of(fileId.getAbsolutePath()).toAbsolutePath().normalize();
    return repoDirectory.relativize(file).toString();
  }
}
