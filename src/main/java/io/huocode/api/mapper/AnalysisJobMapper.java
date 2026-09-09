package io.huocode.api.mapper;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.JobAccepted;
import io.huocode.api.endpoint.rest.model.JobFailed;
import io.huocode.api.endpoint.rest.model.JobProcessing;
import io.huocode.api.model.RepoAnalysisJob;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AnalysisJobMapper {

  public JobAccepted toAccepted(UUID jobId, int estimatedSeconds) {
    return new JobAccepted()
        .jobId(jobId)
        .status(JobAccepted.StatusEnum.PROCESSING)
        .estimatedSeconds(estimatedSeconds);
  }

  public Object toResponse(RepoAnalysisJob job) {
    return switch (job.getStatus()) {
      case COMPLETED -> job.getResult().status(AnalysisResult.StatusEnum.COMPLETED);
      case FAILED ->
          new JobFailed()
              .jobId(job.getJobId())
              .status(JobFailed.StatusEnum.FAILED)
              .errorCode(job.getErrorCode());
      case PROCESSING ->
          new JobProcessing().jobId(job.getJobId()).status(JobProcessing.StatusEnum.PROCESSING);
    };
  }
}
