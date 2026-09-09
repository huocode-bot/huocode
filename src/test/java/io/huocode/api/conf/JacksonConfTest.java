package io.huocode.api.conf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.huocode.api.endpoint.rest.model.FileResult;
import io.huocode.api.endpoint.rest.model.FileResultChurn;
import io.huocode.api.endpoint.rest.model.FileStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class JacksonConfTest {

  @Test
  void nullable_fields_are_omitted_but_mandatory_ones_are_kept() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    new JacksonConf()
        .nonNullSerializationPostProcessor()
        .postProcessAfterInitialization(objectMapper, "objectMapper");

    var fileResult =
        new FileResult()
            .path("src/main/java/Foo.java")
            .status(FileStatus.ANALYZED)
            .complexity(BigDecimal.valueOf(12))
            .churn(new FileResultChurn().commits(5).authors(2));

    var json = objectMapper.readTree(objectMapper.writeValueAsString(fileResult));

    assertTrue(json.has("path"));
    assertTrue(json.has("status"));
    assertTrue(json.has("complexity"));
    assertTrue(json.has("churn"));
    assertFalse(json.has("complexityPercentile"));
    assertFalse(json.has("churnPercentile"));
    assertFalse(json.has("codeHealthScore"));
    assertFalse(json.has("quadrant"));
    assertFalse(json.has("unsupportedLanguage"));
    assertFalse(json.has("errorCode"));
  }
}
