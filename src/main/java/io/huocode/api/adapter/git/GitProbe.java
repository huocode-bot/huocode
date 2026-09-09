package io.huocode.api.adapter.git;

import io.huocode.api.exception.GitCommandFailedException;
import io.huocode.api.utils.GitProcessUtils;
import java.nio.file.Path;
import java.time.Duration;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Getter
@Component
public class GitProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

  private final boolean available;

  public GitProbe() {
    boolean reachable = true;
    try {
      GitProcessUtils.run(Path.of("."), PROBE_TIMEOUT, "--version");
    } catch (GitCommandFailedException e) {
      reachable = false;
    }
    this.available = reachable;
  }
}
