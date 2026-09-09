package io.huocode.api.conf;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConf {

  @Bean
  static BeanPostProcessor nonNullSerializationPostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ObjectMapper objectMapper) {
          objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        }
        return bean;
      }
    };
  }
}
