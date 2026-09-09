package io.huocode.api.adapter.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalysisWindow;
import io.huocode.api.file.bucket.BucketConf;
import io.huocode.api.model.RepoUrl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3ReportStoreTest {

  private static final String BUCKET = "huocode-test-bucket";

  private final BucketConf bucketConf = Mockito.mock(BucketConf.class);
  private final S3Client s3Client = Mockito.mock(S3Client.class);
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final S3ReportStore store = new S3ReportStore(bucketConf, objectMapper);

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");
  private final String sha = "abc123";
  private final AnalysisResult result = aResult();

  @BeforeEach
  void setUp() {
    when(bucketConf.getBucketName()).thenReturn(BUCKET);
    when(bucketConf.getS3Client()).thenReturn(s3Client);
  }

  @Test
  void save_writes_latest_and_sha_keys() {
    store.save(repoUrl, sha, result);

    ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client, times(2)).putObject(requests.capture(), any(RequestBody.class));
    Set<String> keys =
        Set.copyOf(requests.getAllValues().stream().map(PutObjectRequest::key).toList());
    assertTrue(keys.contains("reports/owner/repo/latest.json"));
    assertTrue(keys.contains("results/owner/repo/abc123.json"));
    requests.getAllValues().forEach(request -> assertEquals(BUCKET, request.bucket()));
  }

  @Test
  void find_latest_reads_and_deserializes_result() throws Exception {
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(
            ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                write(result).getBytes(StandardCharsets.UTF_8)));

    AnalysisResult found = store.findLatest(repoUrl).orElseThrow();

    assertEquals("owner/repo", found.getRepo());
    assertEquals(sha, found.getCommitSha());
    verify(s3Client)
        .getObject(
            Mockito.eq(
                GetObjectRequest.builder()
                    .bucket(BUCKET)
                    .key("reports/owner/repo/latest.json")
                    .build()),
            any(ResponseTransformer.class));
  }

  @Test
  void find_returns_empty_when_key_is_missing() {
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenThrow(S3Exception.builder().message("no such key").statusCode(404).build());

    assertTrue(store.findByRepoAndSha(repoUrl, sha).isEmpty());
  }

  private String write(AnalysisResult value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  private AnalysisResult aResult() {
    return new AnalysisResult()
        .status(AnalysisResult.StatusEnum.COMPLETED)
        .repo(repoUrl.toString())
        .commitSha(sha)
        .analyzedAt(Instant.now())
        .strategy(AnalysisResult.StrategyEnum.CLONE)
        .limitations(List.of())
        .analysisWindow(
            new AnalysisWindow()
                .commitsAnalyzed(10)
                .oldestCommitDate(Instant.now())
                .newestCommitDate(Instant.now())
                .distinctAuthors(2))
        .relativeScoringEnabled(false)
        .files(List.of())
        .top5(List.of());
  }
}
