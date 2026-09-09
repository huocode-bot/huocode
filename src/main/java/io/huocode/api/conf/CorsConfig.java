package io.huocode.api.conf;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

  private static final long MAX_AGE_SECONDS = 1800;

  private final String[] allowedOrigins;

  public CorsConfig(@Value("${huocode.cors.allowed-origins:}") String allowedOrigins) {
    this.allowedOrigins =
        Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (allowedOrigins.length == 0) {
      return;
    }
    register(registry, "/analyze/**", "GET", "POST", "OPTIONS");
    register(registry, "/report/**", "GET", "OPTIONS");
    register(registry, "/examples", "GET", "OPTIONS");
  }

  private void register(CorsRegistry registry, String path, String... methods) {
    registry
        .addMapping(path)
        .allowedOriginPatterns(allowedOrigins)
        .allowedMethods(methods)
        .maxAge(MAX_AGE_SECONDS);
  }
}
