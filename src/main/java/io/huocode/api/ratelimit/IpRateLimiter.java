package io.huocode.api.ratelimit;

import io.huocode.api.conf.AnalysisProperties;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class IpRateLimiter {

  static final Duration WINDOW = Duration.ofHours(1);

  private final int maxPerWindow;
  private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

  public IpRateLimiter(AnalysisProperties properties) {
    this.maxPerWindow = properties.getPerIpAnalysisPerHour();
  }

  public boolean tryAcquire(String clientIp) {
    return tryAcquireAt(clientIp, System.currentTimeMillis());
  }

  public int countFor(String clientIp) {
    return countForAt(clientIp, System.currentTimeMillis());
  }

  int countForAt(String clientIp, long nowMillis) {
    if (maxPerWindow <= 0) {
      return 0;
    }
    String key = clientIp == null ? "unknown" : clientIp;
    Deque<Long> window = windows.get(key);
    if (window == null) {
      return 0;
    }
    synchronized (window) {
      evictExpired(window, nowMillis);
      return window.size();
    }
  }

  boolean tryAcquireAt(String clientIp, long nowMillis) {
    if (maxPerWindow <= 0) {
      return true;
    }
    String key = clientIp == null ? "unknown" : clientIp;
    Deque<Long> window = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
    synchronized (window) {
      evictExpired(window, nowMillis);
      if (window.size() >= maxPerWindow) {
        return false;
      }
      window.addLast(nowMillis);
      return true;
    }
  }

  private void evictExpired(Deque<Long> window, long nowMillis) {
    long cutoff = nowMillis - WINDOW.toMillis();
    while (!window.isEmpty() && window.peekFirst() < cutoff) {
      window.removeFirst();
    }
  }
}
