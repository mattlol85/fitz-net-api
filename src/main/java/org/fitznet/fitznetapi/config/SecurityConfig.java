package org.fitznet.fitznetapi.config;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.fitznet.fitznetapi.metrics.FitzNetMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final FitzNetMetrics fitzNetMetrics;

  @Autowired
  public SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter, FitzNetMetrics fitzNetMetrics) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    this.fitzNetMetrics = fitzNetMetrics;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(
                    (request, response, authException) -> {
                      fitzNetMetrics.recordApiFailure(
                          "authentication", Integer.toString(HttpServletResponse.SC_UNAUTHORIZED));
                      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                      response.setContentType("application/json");
                      response
                          .getWriter()
                          .write(
                              "{\"success\":false,\"message\":\"Unauthorized\",\"status\":401}");
                    }))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/user/create", "/user/login")
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/info",
                        "/actuator/prometheus",
                        "/info",
                        "/error")
                    .permitAll()
                    .requestMatchers("/ws-board/**")
                    .permitAll()
                    .requestMatchers("/node/register", "/node/heartbeat", "/node/list")
                    .permitAll()
                    .requestMatchers(HttpMethod.DELETE, "/node/*")
                    .permitAll()
                    .requestMatchers("/user/read", "/user/readAll", "/user/update", "/user/delete")
                    .authenticated()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(
        List.of(
            "https://fitznet.org",
            "https://www.fitznet.org",
            "https://fitznet.doomdns.org",
            "https://api.fitznet.doomdns.org",
            "https://gamerbell.fitznet.doomdns.org",
            "https://logs.fitznet.org",
            "https://logs.fitznet.doomdns.org",
            "http://localhost:3000"));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setExposedHeaders(List.of("Authorization", "Content-Type"));
    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
