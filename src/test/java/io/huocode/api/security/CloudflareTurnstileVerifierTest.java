package io.huocode.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.huocode.api.exception.ChallengeFailedException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CloudflareTurnstileVerifierTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient = mock(HttpClient.class);
  private final String siteverifyUrl = "https://siteverify.test/verify";

  @SuppressWarnings("unchecked")
  private final HttpResponse<String> response = mock(HttpResponse.class);

  private final CloudflareTurnstileVerifier verifier =
      new CloudflareTurnstileVerifier(
          objectMapper, httpClient, "the-secret", siteverifyUrl, Duration.ofSeconds(5));

  @Test
  void verify_succeeds_when_cloudflare_reports_success() throws Exception {
    stubResponse(200, "{\"success\":true}");

    verifier.verify("the-token");

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), any());
    HttpRequest request = requestCaptor.getValue();
    assertEquals(siteverifyUrl, request.uri().toString());
    assertEquals(
        "application/x-www-form-urlencoded",
        request.headers().firstValue("Content-Type").orElseThrow());
    String body = bodyOf(request);
    assertTrue(body.contains("secret=the-secret"));
    assertTrue(body.contains("response=the-token"));
  }

  @Test
  void verify_fails_when_cloudflare_reports_failure() throws Exception {
    stubResponse(200, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

    assertThrows(ChallengeFailedException.class, () -> verifier.verify("the-token"));
  }

  @Test
  void verify_fails_on_non_200_status() throws Exception {
    stubResponse(500, "{\"success\":false}");

    assertThrows(ChallengeFailedException.class, () -> verifier.verify("the-token"));
  }

  @Test
  void verify_fails_on_unparseable_body() throws Exception {
    stubResponse(200, "not-json");

    assertThrows(ChallengeFailedException.class, () -> verifier.verify("the-token"));
  }

  @Test
  void verify_rejects_blank_or_null_token_without_network_call() throws Exception {
    assertThrows(ChallengeFailedException.class, () -> verifier.verify(null));
    assertThrows(ChallengeFailedException.class, () -> verifier.verify("   "));
    verify(httpClient, never()).send(any(), any());
  }

  @Test
  void verify_fails_closed_on_network_error() throws Exception {
    stubSendError(new IOException("connection refused"));

    assertThrows(ChallengeFailedException.class, () -> verifier.verify("the-token"));
  }

  @Test
  void verify_fails_closed_on_interruption_and_restores_flag() throws Exception {
    stubSendError(new InterruptedException("interrupted"));

    assertThrows(ChallengeFailedException.class, () -> verifier.verify("the-token"));
    assertTrue(Thread.currentThread().isInterrupted());
    Thread.interrupted();
  }

  private void stubSendError(Exception error) throws Exception {
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(error);
  }

  @SuppressWarnings("unchecked")
  private void stubResponse(int statusCode, String body) throws Exception {
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    doReturn(response)
        .when(httpClient)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  private static String bodyOf(HttpRequest request) throws Exception {
    StringBuilder builder = new StringBuilder();
    CompletableFuture<Void> done = new CompletableFuture<>();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<java.nio.ByteBuffer>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                builder.append(new String(bytes, StandardCharsets.UTF_8));
              }

              @Override
              public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
              }

              @Override
              public void onComplete() {
                done.complete(null);
              }
            });
    done.get();
    return builder.toString();
  }
}
