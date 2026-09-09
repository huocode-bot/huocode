package io.huocode.api.security;

import io.huocode.api.exception.ChallengeRequiredException;
import io.huocode.api.ratelimit.IpRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TurnstileGate {

  private final TurnstileVerifier turnstileVerifier;
  private final IpRateLimiter ipRateLimiter;
  private final boolean enabled;
  private final int challengeAfter;
  private final Duration verifiedTtl;
  private final Clock clock;
  private final Map<String, Instant> verifiedUntil = new ConcurrentHashMap<>();

  public TurnstileGate(
      TurnstileVerifier turnstileVerifier,
      IpRateLimiter ipRateLimiter,
      @Value("${huocode.turnstile.enabled:false}") boolean enabled,
      @Value("${huocode.turnstile.challenge-after:3}") int challengeAfter,
      @Value("${huocode.turnstile.verified-ttl:PT1H}") Duration verifiedTtl) {
    this(turnstileVerifier, ipRateLimiter, enabled, challengeAfter, verifiedTtl, Clock.systemUTC());
  }

  TurnstileGate(
      TurnstileVerifier turnstileVerifier,
      IpRateLimiter ipRateLimiter,
      boolean enabled,
      int challengeAfter,
      Duration verifiedTtl,
      Clock clock) {
    this.turnstileVerifier = turnstileVerifier;
    this.ipRateLimiter = ipRateLimiter;
    this.enabled = enabled;
    this.challengeAfter = challengeAfter;
    this.verifiedTtl = verifiedTtl;
    this.clock = clock;
  }

  public void enforce(String clientIp, String turnstileToken) {
    if (!enabled) {
      return;
    }
    if (turnstileToken != null && !turnstileToken.isBlank()) {
      turnstileVerifier.verify(turnstileToken);
      verifiedUntil.put(key(clientIp), clock.instant().plus(verifiedTtl));
      return;
    }
    if (!isVerified(clientIp) && ipRateLimiter.countFor(clientIp) >= challengeAfter) {
      throw new ChallengeRequiredException(
          "a verified turnstile token is required before further analyses");
    }
  }

  private boolean isVerified(String clientIp) {
    String key = key(clientIp);
    Instant until = verifiedUntil.get(key);
    if (until == null) {
      return false;
    }
    if (until.isBefore(clock.instant())) {
      verifiedUntil.remove(key);
      return false;
    }
    return true;
  }

  private static String key(String clientIp) {
    return clientIp == null ? "unknown" : clientIp;
  }
}
