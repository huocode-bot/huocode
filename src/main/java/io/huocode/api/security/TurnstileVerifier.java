package io.huocode.api.security;

import io.huocode.api.exception.ChallengeFailedException;

public interface TurnstileVerifier {

  void verify(String turnstileToken) throws ChallengeFailedException;
}
