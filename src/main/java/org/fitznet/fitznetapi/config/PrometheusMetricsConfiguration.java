package org.fitznet.fitznetapi.config;

import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.tracer.common.SpanContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PrometheusMetricsConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public PrometheusConfig prometheusConfig() {
    return PrometheusConfig.DEFAULT;
  }

  @Bean
  @ConditionalOnMissingBean
  public PrometheusRegistry prometheusRegistry() {
    return new PrometheusRegistry();
  }

  @Bean
  @ConditionalOnMissingBean
  public PrometheusMeterRegistry prometheusMeterRegistry(
      PrometheusConfig prometheusConfig,
      PrometheusRegistry prometheusRegistry,
      ObjectProvider<SpanContext> spanContext) {
    return new PrometheusMeterRegistry(
        prometheusConfig, prometheusRegistry, Clock.SYSTEM, spanContext.getIfAvailable());
  }
}
