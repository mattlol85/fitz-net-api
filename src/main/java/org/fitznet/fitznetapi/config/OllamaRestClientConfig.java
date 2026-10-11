package org.fitznet.fitznetapi.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient for outbound calls to a node's local Ollama HTTP API. A generous read timeout since
 * local model cold-load + generation can genuinely take a while; a short connect timeout since a
 * LAN/VPN host that's actually reachable should connect fast. Both come from the {@code ollama.*}
 * properties shared with {@code OllamaChatStreamer}.
 */
@Configuration
public class OllamaRestClientConfig {

  @Bean
  public RestClient ollamaRestClient(
      @Value("${ollama.connect-timeout:5s}") Duration connectTimeout,
      @Value("${ollama.read-timeout:180s}") Duration readTimeout) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(connectTimeout);
    requestFactory.setReadTimeout(readTimeout);
    return RestClient.builder().requestFactory(requestFactory).build();
  }
}
