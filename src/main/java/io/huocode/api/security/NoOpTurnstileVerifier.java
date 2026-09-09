package io.huocode.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(TurnstileVerifier.class)
public class NoOpTurnstileVerifier implements TurnstileVerifier {

  @Override
  public void verify(String turnstileToken) {}
}
