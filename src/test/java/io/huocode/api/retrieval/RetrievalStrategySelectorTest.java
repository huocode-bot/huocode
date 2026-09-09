package io.huocode.api.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.exception.RepoTooLargeException;
import io.huocode.api.model.RetrievalStrategy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetrievalStrategySelectorTest {

  private final RetrievalStrategySelector selector =
      new RetrievalStrategySelector(
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
              2));

  @Test
  void select_uses_api_direct_below_or_at_threshold() {
    assertEquals(RetrievalStrategy.API_DIRECT, selector.select(299));
    assertEquals(RetrievalStrategy.API_DIRECT, selector.select(300));
  }

  @Test
  void select_uses_clone_between_thresholds() {
    assertEquals(RetrievalStrategy.CLONE, selector.select(301));
    assertEquals(RetrievalStrategy.CLONE, selector.select(20000));
  }

  @Test
  void select_rejects_oversized_repositories() {
    assertThrows(RepoTooLargeException.class, () -> selector.select(20001));
  }
}
