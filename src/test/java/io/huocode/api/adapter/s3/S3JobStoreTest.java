package io.huocode.api.adapter.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalysisWindow;
import io.huocode.api.file.bucket.BucketConf;
import io.huocode.api.model.ActiveJobRef;
import io.huocode.api.model.RepoAnalysisJob;
import io.huocode.api.model.RepoUrl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3JobStoreTest {

  private static final String BUCKET = "huocode-test-bucket";

  private final BucketConf bucketConf = Mockito.mock(BucketConf.class);
  private final S3Client s3Client = Mockito.mock(S3Client.class);
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final S3JobStore store = new S3JobStore(bucketConf, objectMapper);

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");
  private final String sha = "abc123";
  private final UUID jobId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  private final RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);

  @BeforeEach
  void setUp() {
    when(bucketConf.getBucketName()).thenReturn(BUCKET);
    when(bucketConf.getS3Client()).thenReturn(s3Client);
  }

  @Test
  void register_active_writes_job_and_pointer_keys() {
    store.registerActive(job);

    ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client, times(2)).putObject(requests.capture(), any(RequestBody.class));
    Set<String> keys =
        Set.copyOf(requests.getAllValues().stream().map(PutObjectRequest::key).toList());
    assertTrue(keys.contains("jobs/" + jobId + ".json"));
    assertTrue(keys.contains("jobs/active/owner/repo/abc123.json"));
    requests.getAllValues().forEach(request -> assertEquals(BUCKET, request.bucket()));
  }

  @Test
  void save_writes_only_the_canonical_job_key() {
    store.save(job);

    ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client, times(1)).putObject(requests.capture(), any(RequestBody.class));
    assertEquals("jobs/" + jobId + ".json", requests.getValue().key());
  }

  @Test
  void find_by_id_reads_and_deserializes_job() throws Exception {
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(
            ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), write(job).getBytes(StandardCharsets.UTF_8)));

    RepoAnalysisJob found = store.findById(jobId).orElseThrow();

    assertEquals(jobId, found.getJobId());
    assertEquals("owner", found.getOwner());
    assertEquals(sha, found.getCommitSha());
    assertEquals(now, found.getCreatedAt());
    verify(s3Client)
        .getObject(
            Mockito.eq(
                GetObjectRequest.builder().bucket(BUCKET).key("jobs/" + jobId + ".json").build()),
            any(ResponseTransformer.class));
  }

  @Test
  void find_active_resolves_pointer_then_job() throws Exception {
    ActiveJobRef ref = new ActiveJobRef(jobId, now);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(
            ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                writeRef(ref).getBytes(StandardCharsets.UTF_8)))
        .thenReturn(
            ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), write(job).getBytes(StandardCharsets.UTF_8)));

    RepoAnalysisJob found = store.findActive(repoUrl, sha).orElseThrow();

    assertEquals(jobId, found.getJobId());
    assertEquals("owner", found.getOwner());
    assertEquals(sha, found.getCommitSha());
    assertEquals(now, found.getCreatedAt());
    verify(s3Client, times(2))
        .getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
  }

  @Test
  void find_returns_empty_when_key_is_missing() {
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenThrow(S3Exception.builder().message("no such key").statusCode(404).build());

    assertTrue(store.findById(jobId).isEmpty());
    assertTrue(store.findActive(repoUrl, sha).isEmpty());
  }

  @Test
  void clear_active_deletes_pointer_when_it_belongs_to_job() throws Exception {
    ActiveJobRef ref = new ActiveJobRef(jobId, now);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(
            ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                writeRef(ref).getBytes(StandardCharsets.UTF_8)));

    store.clearActive(repoUrl, sha, jobId);

    verify(s3Client)
        .deleteObject(
            DeleteObjectRequest.builder()
                .bucket(BUCKET)
                .key("jobs/active/owner/repo/abc123.json")
                .build());
  }

  @Test
  void clear_active_keeps_pointer_of_another_job() throws Exception {
    ActiveJobRef ref = new ActiveJobRef(jobId, now);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(
            ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                writeRef(ref).getBytes(StandardCharsets.UTF_8)));

    store.clearActive(repoUrl, sha, UUID.randomUUID());

    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void count_active_lists_all_active_pointer_objects() {
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenReturn(
            ListObjectsV2Response.builder()
                .contents(
                    S3Object.builder().key("jobs/active/a/b/c.json").build(),
                    S3Object.builder().key("jobs/active/d/e/f.json").build())
                .isTruncated(false)
                .build());

    assertEquals(2L, store.countActive());

    ArgumentCaptor<ListObjectsV2Request> request =
        ArgumentCaptor.forClass(ListObjectsV2Request.class);
    verify(s3Client).listObjectsV2(request.capture());
    assertEquals("jobs/active/", request.getValue().prefix());
    assertEquals(BUCKET, request.getValue().bucket());
  }

  @Test
  void count_active_follows_truncation_token() {
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenReturn(
            ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("jobs/active/a/b/c.json").build())
                .isTruncated(true)
                .nextContinuationToken("next")
                .build())
        .thenReturn(
            ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("jobs/active/d/e/f.json").build())
                .isTruncated(false)
                .build());

    assertEquals(2L, store.countActive());
  }

  @Test
  void delete_removes_canonical_job_key() {
    store.delete(jobId);

    verify(s3Client)
        .deleteObject(
            DeleteObjectRequest.builder().bucket(BUCKET).key("jobs/" + jobId + ".json").build());
  }

  private String write(RepoAnalysisJob value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  private String writeRef(ActiveJobRef value) throws Exception {
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
