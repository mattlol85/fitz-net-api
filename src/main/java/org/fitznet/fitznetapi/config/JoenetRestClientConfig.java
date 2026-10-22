package org.fitznet.fitznetapi.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the Joenet proxy's outbound calls to Radarr/Sonarr on the LAN. A short connect
 * timeout since a reachable LAN host connects fast, and a moderate read timeout since some
 * endpoints (library lookups, command queues) can be slow. Both come from the {@code joenet.*}
 * properties.
 */
@Configuration
public class JoenetRestClientConfig {

  @Bean
  public RestClient joenetRestClient(
      @Value("${joenet.connect-timeout:5s}") Duration connectTimeout,
      @Value("${joenet.read-timeout:30s}") Duration readTimeout) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(connectTimeout);
    requestFactory.setReadTimeout(readTimeout);
    return RestClient.builder().requestFactory(requestFactory).build();
  }
}
