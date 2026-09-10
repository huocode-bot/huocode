package io.huocode.api.endpoint.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.huocode.api.conf.FacadeIT;
import io.huocode.api.port.ReportStore;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AnalyzeFlowIT extends FacadeIT {

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @MockBean private ReportStore reportStore;

  @Test
  void health_endpoint_replies_up() {
    ResponseEntity<String> response =
        restTemplate.getForEntity(baseUrl() + "/health", String.class);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
  }

  @Test
  void ping_endpoint_replies_pong() {
    ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/ping", String.class);

    assertEquals("pong", response.getBody());
  }

  @Test
  void analyze_small_java_repo_returns_sync_result() {
    Map<String, String> request =
        Map.of("repoUrl", "https://github.com/spring-guides/gs-spring-boot");

    ResponseEntity<String> response =
        restTemplate.postForEntity(baseUrl() + "/analyze", request, String.class);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }
}
