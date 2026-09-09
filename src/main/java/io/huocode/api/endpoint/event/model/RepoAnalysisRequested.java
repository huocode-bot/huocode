package io.huocode.api.endpoint.event.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RepoAnalysisRequested extends PojaEvent {

  public static final Duration MAX_CONSUMER_DURATION = Duration.ofMinutes(15);

  private UUID jobId;
  private String repoUrl;

  public RepoAnalysisRequested(UUID jobId, String repoUrl) {
    this.jobId = jobId;
    this.repoUrl = repoUrl;
  }

  @Override
  public Duration maxConsumerDuration() {
    return MAX_CONSUMER_DURATION;
  }

  @Override
  public Duration maxConsumerBackoffBetweenRetries() {
    return Duration.ofSeconds(30);
  }
}
