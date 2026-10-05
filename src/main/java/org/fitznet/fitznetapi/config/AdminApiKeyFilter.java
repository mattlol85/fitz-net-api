package org.fitznet.fitznetapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.fitznet.fitznetapi.model.Permissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service access pattern: a request carrying a valid {@code X-Admin-Key} header is
 * authenticated as {@link #PRINCIPAL} with the ADMIN permission, so scripts can manage user
 * permissions without a user login. Disabled entirely when {@code admin.api-key} is unset.
 */
@Component
public class AdminApiKeyFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Admin-Key";
  public static final String PRINCIPAL = "admin-api-key";

  private final byte[] configuredKey;

  public AdminApiKeyFilter(@Value("${admin.api-key:}") String apiKey) {
    this.configuredKey =
        apiKey == null || apiKey.isBlank() ? null : apiKey.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String provided = request.getHeader(HEADER);
    if (configuredKey != null
        && provided != null
        && SecurityContextHolder.getContext().getAuthentication() == null
        && MessageDigest.isEqual(configuredKey, provided.getBytes(StandardCharsets.UTF_8))) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  PRINCIPAL,
                  null,
                  List.of(new SimpleGrantedAuthority(Permissions.authority(Permissions.ADMIN)))));
    }
    filterChain.doFilter(request, response);
  }
}
