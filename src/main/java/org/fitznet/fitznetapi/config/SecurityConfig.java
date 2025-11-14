package org.fitznet.fitznetapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  // Fixme: Remove debug auth
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable) // Use the new lambda-based configuration
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/encrypt", "/decrypt")
                    .permitAll()
                    .requestMatchers("/user/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated());
    return http.build();
  }
}
