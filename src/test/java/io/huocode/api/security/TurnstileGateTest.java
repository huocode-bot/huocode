package io.huocode.api.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.huocode.api.exception.ChallengeFailedException;
import io.huocode.api.exception.ChallengeRequiredException;
import io.huocode.api.ratelimit.IpRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TurnstileGateTest {

  private final TurnstileVerifier verifier = mock(TurnstileVerifier.class);
  private final IpRateLimiter ipRateLimiter = mock(IpRateLimiter.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

  private TurnstileGate gate(int challengeAfter, boolean enabled, Duration verifiedTtl) {
    return new TurnstileGate(verifier, ipRateLimiter, enabled, challengeAfter, verifiedTtl, clock);
  }

  @Test
  void disabled_gate_is_a_no_op() {
    TurnstileGate gate = gate(3, false, Duration.ofHours(1));
    when(ipRateLimiter.countFor("1.2.3.4")).thenReturn(100);

    assertDoesNotThrow(() -> gate.enforce("1.2.3.4", null));
    assertDoesNotThrow(() -> gate.enforce("1.2.3.4", "any-token"));

    verify(verifier, never()).verify("any-token");
  }

  @Test
  void no_token_below_threshold_passes() {
    TurnstileGate gate = gate(3, true, Duration.ofHours(1));
    when(ipRateLimiter.countFor("1.2.3.4")).thenReturn(2);

    assertDoesNotThrow(() -> gate.enforce("1.2.3.4", null));
    verify(verifier, never()).verify(any());
  }

  @Test
  void no_token_at_threshold_requires_challenge() {
    TurnstileGate gate = gate(3, true, Duration.ofHours(1));
    when(ipRateLimiter.countFor("1.2.3.4")).thenReturn(3);

    assertThrows(ChallengeRequiredException.class, () -> gate.enforce("1.2.3.4", null));
    verify(verifier, never()).verify(any());
  }

  @Test
  void blank_token_is_treated_as_absent() {
    TurnstileGate gate = gate(3, true, Duration.ofHours(1));
    when(ipRateLimiter.countFor("1.2.3.4")).thenReturn(3);

    assertThrows(ChallengeRequiredException.class, () -> gate.enforce("1.2.3.4", "   "));
    verify(verifier, never()).verify(any());
  }

  @Test
  void valid_token_verifies_and_marks_ip_verified() {
    TurnstileGate gate = gate(3, true, Duration.ofHours(1));
    when(ipRateLimiter.countFor("1.2.3.4")).thenReturn(3);

    assertDoesNotThrow(() -> gate.enforce("1.2.3.4", "good-token"));

    verify(verifier).verify("good-token");
    assertDoesNotThrow(() -> gate.enforce("1.2.3.4", null));
  }

  @Test
  void invalid_token_propagates_and_does_not_mark_ip_verified() throws Exception {
    TurnstileGate gate = gate(3, true, Duration.ofHours(1));
    when(ipRateLimiter.countFor("1.2.3.4")).thenReturn(3);
    doThrow(new ChallengeFailedException("bad token")).when(verifier).verify("bad-token");

    assertThrows(ChallengeFailedException.class, () -> gate.enforce("1.2.3.4", "bad-token"));
    assertThrows(ChallengeRequiredException.class, () -> gate.enforce("1.2.3.4", null));
  }

  @Test
  void verified_status_expires_after_ttl() {
    TurnstileGate gate = gate(3, true, Duration.ofHours(1));
    when(ipRateLimiter.countFor("1.2.3.4")).thenReturn(3);

    assertDoesNotThrow(() -> gate.enforce("1.2.3.4", "good-token"));
    clock.advance(Duration.ofHours(1).plusSeconds(1));
    assertThrows(ChallengeRequiredException.class, () -> gate.enforce("1.2.3.4", null));
  }

  @Test
  void concurrent_counts_use_unknown_key_for_null_ip() {
    TurnstileGate gate = gate(1, true, Duration.ofHours(1));
    when(ipRateLimiter.countFor(null)).thenReturn(1);

    assertThrows(ChallengeRequiredException.class, () -> gate.enforce(null, null));
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
