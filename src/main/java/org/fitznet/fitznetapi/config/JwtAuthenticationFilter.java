package org.fitznet.fitznetapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.fitznet.fitznetapi.model.Permissions;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.service.UserService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

  @Autowired private JwtUtil jwtUtil;
  // Lazy: UserService -> repository -> PasswordEncoder, which is defined in SecurityConfig that
  // depends on this filter.
  @Autowired @Lazy private UserService userService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    final String authorizationHeader = request.getHeader("Authorization");

    String username = null;
    String jwt = null;

    // Extract JWT token from Authorization header
    if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
      jwt = authorizationHeader.substring(7);
      try {
        username = jwtUtil.extractUsername(jwt);
      } catch (Exception e) {
        log.warn("Error extracting username from JWT: {}", e.getMessage());
      }
    }

    // Validate token and set authentication
    if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
      // Permissions come from the database, not the token, so revocation is immediate.
      User user = null;
      try {
        user = jwtUtil.validateToken(jwt) ? userService.readByUsername(username) : null;
      } catch (RuntimeException e) {
        // e.g. Mongo unavailable: leave the request unauthenticated so public endpoints still work
        log.warn("Could not load user {} during JWT authentication: {}", username, e.getMessage());
      }
      if (user != null) {
        Set<String> granted = user.getPermissions() == null ? Set.of() : user.getPermissions();
        List<GrantedAuthority> authorities =
            granted.stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(Permissions.authority(p)))
                .toList();
        UsernamePasswordAuthenticationToken authenticationToken =
            new UsernamePasswordAuthenticationToken(username, null, authorities);
        authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        log.debug("JWT authentication successful for user: {}", username);
      }
    }

    filterChain.doFilter(request, response);
  }
}

