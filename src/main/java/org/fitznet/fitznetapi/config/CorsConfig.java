package org.fitznet.fitznetapi.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

  @Bean
  public CorsFilter corsFilter() {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    CorsConfiguration config = new CorsConfiguration();

    config.setAllowCredentials(true);

    // Just for testing...
    config.setAllowedOrigins(Arrays.asList(
        "http://localhost:3000",  // React default
        "http://localhost:4200",  // Angular default
        "http://localhost:5173",  // Vite default
        "http://localhost:8080"   // Vue default
    ));

    config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

    config.setAllowedHeaders(List.of("*"));

    config.setExposedHeaders(Arrays.asList("Authorization", "Content-Type"));

    config.setMaxAge(3600L);

    source.registerCorsConfiguration("/**", config);
    return new CorsFilter(source);
  }
}

