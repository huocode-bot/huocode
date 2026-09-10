package io.huocode.api.security;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.huocode.api.conf.FacadeIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "huocode.turnstile.enabled=true")
class TurnstileContextTest extends FacadeIT {

  @Autowired private TurnstileGate turnstileGate;
  @Autowired private TurnstileVerifier turnstileVerifier;

  @Test
  void context_loads_with_turnstile_enabled() {
    assertNotNull(turnstileGate);
    assertInstanceOf(CloudflareTurnstileVerifier.class, turnstileVerifier);
  }
}
