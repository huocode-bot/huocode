package io.huocode.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TurnstileVerifierConfiguration {

  @Bean
  @ConditionalOnProperty(name = "huocode.turnstile.enabled", havingValue = "true")
  public TurnstileVerifier cloudflareTurnstileVerifier(
      ObjectMapper objectMapper,
      @Value("${huocode.turnstile.secret-key:}") String secretKey,
      @Value(
              "${huocode.turnstile.siteverify-url:"
                  + CloudflareTurnstileVerifier.DEFAULT_SITEVERIFY_URL
                  + "}")
          String siteverifyUrl,
      @Value("${huocode.turnstile.request-timeout:PT5S}") Duration requestTimeout) {
    return new CloudflareTurnstileVerifier(objectMapper, secretKey, siteverifyUrl, requestTimeout);
  }

  @Bean
  @ConditionalOnMissingBean(TurnstileVerifier.class)
  public TurnstileVerifier noOpTurnstileVerifier() {
    return new NoOpTurnstileVerifier();
  }
}
