package io.huocode.api.security;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.huocode.api.conf.FacadeIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TurnstileDisabledContextTest extends FacadeIT {

  @Autowired private TurnstileGate turnstileGate;
  @Autowired private TurnstileVerifier turnstileVerifier;

  @Test
  void context_loads_with_turnstile_disabled_by_default() {
    assertNotNull(turnstileGate);
    assertInstanceOf(NoOpTurnstileVerifier.class, turnstileVerifier);
  }
}
