package io.huocode.api.conf;

import org.springframework.test.context.DynamicPropertyRegistry;

public class EnvConf {

  void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("huocode.turnstile.secret-key", () -> "test-secret-key");
    registry.add("GITHUB_TOKEN", () -> System.getenv("GITHUB_TOKEN"));
  }
}
