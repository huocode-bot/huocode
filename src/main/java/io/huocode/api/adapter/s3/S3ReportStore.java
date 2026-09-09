package io.huocode.api.adapter.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.file.bucket.BucketConf;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.ReportStore;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@RequiredArgsConstructor
public class S3ReportStore implements ReportStore {

  private static final String LATEST_KEY = "reports/%s/%s/latest.json";
  private static final String RESULT_KEY = "results/%s/%s/%s.json";

  private final BucketConf bucketConf;
  private final ObjectMapper objectMapper;

  @Override
  public Optional<AnalysisResult> findLatest(RepoUrl repoUrl) {
    return find(latestKey(repoUrl));
  }

  @Override
  public Optional<AnalysisResult> findByRepoAndSha(RepoUrl repoUrl, String sha) {
    return find(resultKey(repoUrl, sha));
  }

  @Override
  public void save(RepoUrl repoUrl, String sha, AnalysisResult result) {
    String json = write(result);
    putObject(latestKey(repoUrl), json);
    putObject(resultKey(repoUrl, sha), json);
  }

  @SneakyThrows
  private Optional<AnalysisResult> find(String key) {
    try {
      ResponseBytes<GetObjectResponse> response =
          bucketConf.getS3Client().getObject(getObjectRequest(key), ResponseTransformer.toBytes());
      return Optional.of(objectMapper.readValue(response.asByteArray(), AnalysisResult.class));
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

  @SneakyThrows
  private String write(AnalysisResult result) {
    return objectMapper.writeValueAsString(result);
  }

  private String latestKey(RepoUrl repoUrl) {
    return LATEST_KEY.formatted(repoUrl.owner(), repoUrl.repo());
  }

  private String resultKey(RepoUrl repoUrl, String sha) {
    return RESULT_KEY.formatted(repoUrl.owner(), repoUrl.repo(), sha);
  }
}
