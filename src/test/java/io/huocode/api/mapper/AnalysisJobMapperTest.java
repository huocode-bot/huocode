package io.huocode.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AsyncFailureCode;
import io.huocode.api.endpoint.rest.model.JobAccepted;
import io.huocode.api.endpoint.rest.model.JobFailed;
import io.huocode.api.endpoint.rest.model.JobProcessing;
import io.huocode.api.model.RepoAnalysisJob;
import io.huocode.api.model.RepoUrl;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalysisJobMapperTest {

  private final AnalysisJobMapper mapper = new AnalysisJobMapper();
  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");
  private final UUID jobId = UUID.randomUUID();

  @Test
  void to_accepted_maps_job_id_and_constant_estimate() {
    JobAccepted accepted = mapper.toAccepted(jobId, 60);

    assertEquals(jobId, accepted.getJobId());
    assertEquals(JobAccepted.StatusEnum.PROCESSING, accepted.getStatus());
    assertEquals(60, accepted.getEstimatedSeconds());
  }

  @Test
  void to_response_maps_processing_job() {
    RepoAnalysisJob job =
        RepoAnalysisJob.pending(repoUrl, "abc", jobId, Instant.now()).started(Instant.now());

    Object response = mapper.toResponse(job);

    assertInstanceOf(JobProcessing.class, response);
    JobProcessing processing = (JobProcessing) response;
    assertEquals(jobId, processing.getJobId());
    assertEquals(JobProcessing.StatusEnum.PROCESSING, processing.getStatus());
  }

  @Test
  void to_response_maps_failed_job_with_error_code() {
    RepoAnalysisJob job =
        RepoAnalysisJob.pending(repoUrl, "abc", jobId, Instant.now())
            .failed(AsyncFailureCode.GITHUB_RATE_LIMIT_REACHED);

    Object response = mapper.toResponse(job);

    assertInstanceOf(JobFailed.class, response);
    JobFailed failed = (JobFailed) response;
    assertEquals(jobId, failed.getJobId());
    assertEquals(JobFailed.StatusEnum.FAILED, failed.getStatus());
    assertEquals(AsyncFailureCode.GITHUB_RATE_LIMIT_REACHED, failed.getErrorCode());
  }

  @Test
  void to_response_maps_completed_job_to_completed_result() {
    AnalysisResult result = new AnalysisResult().status(AnalysisResult.StatusEnum.COMPLETED);
    RepoAnalysisJob job =
        RepoAnalysisJob.pending(repoUrl, "abc", jobId, Instant.now()).completed(result);

    Object response = mapper.toResponse(job);

    assertInstanceOf(AnalysisResult.class, response);
    assertSame(result, response);
    assertEquals(AnalysisResult.StatusEnum.COMPLETED, result.getStatus());
  }
}
