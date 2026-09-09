package io.huocode.api.concurrency;

import io.huocode.api.conf.AnalysisProperties;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
public class ConcurrencyGuard {

  private final AtomicInteger inFlight = new AtomicInteger();
  @Getter private final int capacity;

  public ConcurrencyGuard(AnalysisProperties properties) {
    this.capacity = properties.getMaxConcurrentSyncAnalysis();
  }

  public boolean tryAcquire() {
    if (capacity <= 0) {
      inFlight.incrementAndGet();
      return true;
    }
    while (true) {
      int current = inFlight.get();
      if (current >= capacity) {
        return false;
      }
      if (inFlight.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  public void release() {
    inFlight.decrementAndGet();
  }

  public int inFlight() {
    return inFlight.get();
  }
}
