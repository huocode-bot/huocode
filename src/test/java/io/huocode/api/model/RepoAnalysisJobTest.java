package io.huocode.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AsyncFailureCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepoAnalysisJobTest {

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");
  private final String sha = "abc123";
  private final UUID jobId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void pending_starts_processing_with_repo_metadata() {
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);

    assertEquals(jobId, job.getJobId());
    assertEquals("owner", job.getOwner());
    assertEquals("repo", job.getRepo());
    assertEquals("https://github.com/owner/repo", job.canonicalUrl());
    assertEquals(sha, job.getCommitSha());
    assertEquals(RepoAnalysisJob.Status.PROCESSING, job.getStatus());
    assertNull(job.getErrorCode());
    assertNull(job.getResult());
    assertEquals(now, job.getCreatedAt());
    assertNull(job.getStartedAt());
    assertFalse(job.isTerminal());
  }

  @Test
  void started_records_processing_start() {
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);

    assertSame(job, job.started(now));
    assertEquals(now, job.getStartedAt());
  }

  @Test
  void completed_is_terminal_with_result() {
    AnalysisResult result = new AnalysisResult();
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);

    assertSame(job, job.completed(result));
    assertEquals(RepoAnalysisJob.Status.COMPLETED, job.getStatus());
    assertSame(result, job.getResult());
    assertTrue(job.isTerminal());
  }

  @Test
  void failed_is_terminal_with_error_code() {
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);

    assertSame(job, job.failed(AsyncFailureCode.GITHUB_RATE_LIMIT_REACHED));
    assertEquals(RepoAnalysisJob.Status.FAILED, job.getStatus());
    assertEquals(AsyncFailureCode.GITHUB_RATE_LIMIT_REACHED, job.getErrorCode());
    assertTrue(job.isTerminal());
  }

  @Test
  void freshly_started_job_within_window_is_not_stale() {
    RepoAnalysisJob job =
        RepoAnalysisJob.pending(repoUrl, sha, jobId, now).started(now.minus(Duration.ofMinutes(1)));

    assertTrue(job.isFreshlyStarted(Duration.ofMinutes(15), now));
    assertFalse(job.isFreshlyStarted(Duration.ofSeconds(30), now));
  }

  @Test
  void processing_job_without_start_is_not_freshly_started() {
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);

    assertFalse(job.isFreshlyStarted(Duration.ofMinutes(15), now));
  }

  @Test
  void is_expired_after_ttl() {
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);

    assertFalse(job.isExpired(Duration.ofHours(48), now.plus(Duration.ofHours(47))));
    assertTrue(job.isExpired(Duration.ofHours(48), now.plus(Duration.ofHours(49))));
  }
}
