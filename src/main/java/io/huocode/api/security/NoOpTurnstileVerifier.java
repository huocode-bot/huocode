package io.huocode.api.security;

public class NoOpTurnstileVerifier implements TurnstileVerifier {

  @Override
  public void verify(String turnstileToken) {}
}
