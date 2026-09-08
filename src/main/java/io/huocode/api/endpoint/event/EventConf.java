package io.huocode.api.endpoint.event;

import io.huocode.api.PojaGenerated;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@PojaGenerated
@Configuration
public class EventConf {
  private final Region region;

  public EventConf(@Value("eu-west-3") Region region) {
    this.region = region;
  }

  @Bean
  public SqsClient getSqsClient() {
    return SqsClient.builder().region(region).build();
  }
}
