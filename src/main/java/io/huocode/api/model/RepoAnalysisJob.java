package io.huocode.api.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AsyncFailureCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepoAnalysisJob {

  public enum Status {
    PROCESSING,
    COMPLETED,
    FAILED
  }

  private UUID jobId;
  private String owner;
  private String repo;
  private String commitSha;
  private Status status;
  private AsyncFailureCode errorCode;
  private AnalysisResult result;
  private Instant createdAt;
  private Instant startedAt;

  public static RepoAnalysisJob pending(
      RepoUrl repoUrl, String commitSha, UUID jobId, Instant createdAt) {
    return new RepoAnalysisJob(
        jobId,
        repoUrl.owner(),
        repoUrl.repo(),
        commitSha,
        Status.PROCESSING,
        null,
        null,
        createdAt,
        null);
  }

  public String canonicalUrl() {
    return "https://github.com/" + owner + "/" + repo;
  }

  public RepoAnalysisJob started(Instant now) {
    this.startedAt = now;
    return this;
  }

  public RepoAnalysisJob completed(AnalysisResult result) {
    this.status = Status.COMPLETED;
    this.result = result;
    return this;
  }

  public RepoAnalysisJob failed(AsyncFailureCode failureCode) {
    this.status = Status.FAILED;
    this.errorCode = failureCode;
    return this;
  }

  @JsonIgnore
  public boolean isTerminal() {
    return status == Status.COMPLETED || status == Status.FAILED;
  }

  @JsonIgnore
  public boolean isFreshlyStarted(Duration processingWindow, Instant now) {
    return status == Status.PROCESSING
        && startedAt != null
        && !startedAt.plus(processingWindow).isBefore(now);
  }

  @JsonIgnore
  public boolean isExpired(Duration ttl, Instant now) {
    return createdAt.plus(ttl).isBefore(now);
  }
}
