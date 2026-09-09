package io.huocode.api.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.huocode.api.exception.ChallengeFailedException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "huocode.turnstile.enabled", havingValue = "true")
public class CloudflareTurnstileVerifier implements TurnstileVerifier {

  private static final String DEFAULT_SITEVERIFY_URL =
      "https://challenges.cloudflare.com/turnstile/v0/siteverify";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String secretKey;
  private final String siteverifyUrl;
  private final Duration requestTimeout;

  public CloudflareTurnstileVerifier(
      ObjectMapper objectMapper,
      @Value("${huocode.turnstile.secret-key:}") String secretKey,
      @Value("${huocode.turnstile.siteverify-url:" + DEFAULT_SITEVERIFY_URL + "}")
          String siteverifyUrl,
      @Value("${huocode.turnstile.request-timeout:PT5S}") Duration requestTimeout) {
    this(
        objectMapper,
        HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
        secretKey,
        siteverifyUrl,
        requestTimeout);
  }

  CloudflareTurnstileVerifier(
      ObjectMapper objectMapper,
      HttpClient httpClient,
      String secretKey,
      String siteverifyUrl,
      Duration requestTimeout) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.secretKey = secretKey;
    this.siteverifyUrl = siteverifyUrl;
    this.requestTimeout = requestTimeout;
  }

  @Override
  public void verify(String turnstileToken) {
    if (turnstileToken == null || turnstileToken.isBlank()) {
      throw verifierFailure();
    }
    String body = "secret=" + encode(secretKey) + "&response=" + encode(turnstileToken);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(siteverifyUrl))
            .timeout(requestTimeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw verifierFailure();
    }
    if (response.statusCode() != 200) {
      throw verifierFailure();
    }
    JsonNode json;
    try {
      json = objectMapper.readTree(response.body());
    } catch (IOException e) {
      throw verifierFailure();
    }
    if (!json.path("success").asBoolean(false)) {
      throw verifierFailure();
    }
  }

  private static ChallengeFailedException verifierFailure() {
    return new ChallengeFailedException(
        "the supplied turnstile token was missing, invalid, or expired");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
