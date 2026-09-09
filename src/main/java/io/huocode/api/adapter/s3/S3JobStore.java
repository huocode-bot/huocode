package io.huocode.api.adapter.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.huocode.api.file.bucket.BucketConf;
import io.huocode.api.model.ActiveJobRef;
import io.huocode.api.model.RepoAnalysisJob;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.JobStore;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@RequiredArgsConstructor
public class S3JobStore implements JobStore {

  private static final String JOB_KEY = "jobs/%s.json";
  private static final String ACTIVE_KEY = "jobs/active/%s/%s/%s.json";

  private final BucketConf bucketConf;
  private final ObjectMapper objectMapper;

  @Override
  public Optional<RepoAnalysisJob> findById(UUID jobId) {
    return find(jobKey(jobId), RepoAnalysisJob.class);
  }

  @Override
  public Optional<RepoAnalysisJob> findActive(RepoUrl repoUrl, String commitSha) {
    Optional<ActiveJobRef> ref = find(activeKey(repoUrl, commitSha), ActiveJobRef.class);
    return ref.map(activeJobRef -> activeJobRef.getJobId()).flatMap(this::findById);
  }

  @Override
  public void registerActive(RepoAnalysisJob job) {
    save(job);
    putObject(activeKey(job), write(new ActiveJobRef(job.getJobId(), job.getCreatedAt())));
  }

  @Override
  public void save(RepoAnalysisJob job) {
    putObject(jobKey(job.getJobId()), write(job));
  }

  @Override
  public void clearActive(RepoUrl repoUrl, String commitSha, UUID jobId) {
    Optional<ActiveJobRef> ref = find(activeKey(repoUrl, commitSha), ActiveJobRef.class);
    if (ref.isPresent() && ref.get().getJobId().equals(jobId)) {
      deleteObject(activeKey(repoUrl, commitSha));
    }
  }

  @Override
  public void delete(UUID jobId) {
    deleteObject(jobKey(jobId));
  }

  @SneakyThrows
  private <T> Optional<T> find(String key, Class<T> type) {
    try {
      ResponseBytes<GetObjectResponse> response =
          bucketConf.getS3Client().getObject(getObjectRequest(key), ResponseTransformer.toBytes());
      return Optional.of(objectMapper.readValue(response.asByteArray(), type));
    } catch (S3Exception e) {
      if (e instanceof NoSuchKeyException || Integer.valueOf(404).equals(e.statusCode())) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private GetObjectRequest getObjectRequest(String key) {
    return GetObjectRequest.builder().bucket(bucketConf.getBucketName()).key(key).build();
  }

  private void putObject(String key, String json) {
    bucketConf
        .getS3Client()
        .putObject(
            PutObjectRequest.builder().bucket(bucketConf.getBucketName()).key(key).build(),
            RequestBody.fromString(json));
  }

  private void deleteObject(String key) {
    bucketConf
        .getS3Client()
        .deleteObject(
            DeleteObjectRequest.builder().bucket(bucketConf.getBucketName()).key(key).build());
  }

  @SneakyThrows
  private String write(RepoAnalysisJob job) {
    return objectMapper.writeValueAsString(job);
  }

  @SneakyThrows
  private String write(ActiveJobRef ref) {
    return objectMapper.writeValueAsString(ref);
  }

  private String jobKey(UUID jobId) {
    return JOB_KEY.formatted(jobId);
  }

  private String activeKey(RepoUrl repoUrl, String commitSha) {
    return ACTIVE_KEY.formatted(repoUrl.owner(), repoUrl.repo(), commitSha);
  }

  private String activeKey(RepoAnalysisJob job) {
    return ACTIVE_KEY.formatted(job.getOwner(), job.getRepo(), job.getCommitSha());
  }
}
