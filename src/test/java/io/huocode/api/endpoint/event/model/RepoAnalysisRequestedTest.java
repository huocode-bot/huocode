package io.huocode.api.endpoint.event.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepoAnalysisRequestedTest {

  @Test
  void json_round_trip_preserves_payload() throws Exception {
    UUID jobId = UUID.randomUUID();
    RepoAnalysisRequested event = new RepoAnalysisRequested(jobId, "https://github.com/owner/repo");

    String json = new ObjectMapper().writeValueAsString(event);
    RepoAnalysisRequested read = new ObjectMapper().readValue(json, RepoAnalysisRequested.class);

    assertEquals(jobId, read.getJobId());
    assertEquals("https://github.com/owner/repo", read.getRepoUrl());
  }

  @Test
  void consumer_durations_cover_the_analysis_window() {
    RepoAnalysisRequested event =
        new RepoAnalysisRequested(UUID.randomUUID(), "https://github.com/owner/repo");

    assertEquals(Duration.ofMinutes(15), event.maxConsumerDuration());
    assertEquals(Duration.ofSeconds(30), event.maxConsumerBackoffBetweenRetries());
  }
}
