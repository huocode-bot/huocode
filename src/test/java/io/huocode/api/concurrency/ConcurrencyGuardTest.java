package io.huocode.api.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.conf.AnalysisProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConcurrencyGuardTest {

  private AnalysisProperties properties(int maxConcurrentSyncAnalysis) {
    return new AnalysisProperties(
        "",
        Duration.ofSeconds(5),
        300,
        20000,
        15,
        10,
        500,
        Duration.ofSeconds(10),
        1048576,
        Duration.ofHours(48),
        60,
        Duration.ofMinutes(15),
        10,
        60,
        maxConcurrentSyncAnalysis,
        10);
  }

  @Test
  void acquires_up_to_capacity_then_rejects() {
    ConcurrencyGuard guard = new ConcurrencyGuard(properties(2));

    assertTrue(guard.tryAcquire());
    assertTrue(guard.tryAcquire());
    assertFalse(guard.tryAcquire());
    assertEquals(2, guard.inFlight());
  }

  @Test
  void release_frees_a_slot() {
    ConcurrencyGuard guard = new ConcurrencyGuard(properties(1));

    assertTrue(guard.tryAcquire());
    guard.release();
    assertTrue(guard.tryAcquire());
  }

  @Test
  void capacity_zero_means_unlimited() {
    ConcurrencyGuard guard = new ConcurrencyGuard(properties(0));

    for (int i = 0; i < 100; i++) {
      assertTrue(guard.tryAcquire());
    }
    assertEquals(100, guard.inFlight());
  }
}
