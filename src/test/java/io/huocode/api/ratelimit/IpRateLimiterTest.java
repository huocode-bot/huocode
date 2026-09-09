package io.huocode.api.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.conf.AnalysisProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IpRateLimiterTest {

  private IpRateLimiter limiter(int perIpAnalysisPerHour) {
    return new IpRateLimiter(
        new AnalysisProperties(
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
            perIpAnalysisPerHour,
            60,
            2));
  }

  @Test
  void allows_up_to_limit_within_window() {
    IpRateLimiter limiter = limiter(3);

    assertTrue(limiter.tryAcquireAt("1.2.3.4", 0L));
    assertTrue(limiter.tryAcquireAt("1.2.3.4", 100L));
    assertTrue(limiter.tryAcquireAt("1.2.3.4", 200L));
    assertFalse(limiter.tryAcquireAt("1.2.3.4", 300L));
  }

  @Test
  void each_ip_is_limited_independently() {
    IpRateLimiter limiter = limiter(1);

    assertTrue(limiter.tryAcquireAt("1.2.3.4", 0L));
    assertFalse(limiter.tryAcquireAt("1.2.3.4", 100L));
    assertTrue(limiter.tryAcquireAt("5.6.7.8", 0L));
  }

  @Test
  void slots_expire_after_one_hour_window() {
    IpRateLimiter limiter = limiter(1);

    assertTrue(limiter.tryAcquireAt("1.2.3.4", 0L));
    assertFalse(limiter.tryAcquireAt("1.2.3.4", 3_599_999L));
    assertTrue(limiter.tryAcquireAt("1.2.3.4", 3_600_001L));
  }

  @Test
  void limit_zero_means_unlimited() {
    IpRateLimiter limiter = limiter(0);

    assertTrue(limiter.tryAcquireAt("1.2.3.4", 0L));
    assertTrue(limiter.tryAcquireAt("1.2.3.4", 100L));
    assertTrue(limiter.tryAcquireAt("1.2.3.4", 200L));
  }
}
