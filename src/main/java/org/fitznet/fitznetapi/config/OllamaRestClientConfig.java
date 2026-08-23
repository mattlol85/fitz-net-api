package org.fitznet.fitznetapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient for outbound calls to a node's local Ollama HTTP API. A generous read timeout since
 * local model cold-load + generation can genuinely take a while; a short connect timeout since a
 * LAN/VPN host that's actually reachable should connect fast.
 */
@Configuration
public class OllamaRestClientConfig {

  @Bean
  public RestClient ollamaRestClient() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(5_000);
    requestFactory.setReadTimeout(180_000);
    return RestClient.builder().requestFactory(requestFactory).build();
  }
}
