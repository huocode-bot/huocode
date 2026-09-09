package io.huocode.api.endpoint.rest.controller.health;

import io.huocode.api.endpoint.rest.model.HealthCheck200Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

  @GetMapping("/health")
  public HealthCheck200Response healthCheck() {
    return new HealthCheck200Response().status("UP");
  }
}
