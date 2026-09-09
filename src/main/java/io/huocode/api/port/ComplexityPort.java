package io.huocode.api.port;

import io.huocode.api.model.ComplexityResult;
import java.nio.file.Path;
import java.util.Collection;

public interface ComplexityPort {

  ComplexityResult analyze(Path repoDirectory, Collection<String> relativePaths);
}
