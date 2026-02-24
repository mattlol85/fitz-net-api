package org.fitznet.fitznetapi.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

  @Mock private JwtUtil jwtUtil;

  @InjectMocks private JwtAuthenticationFilter jwtAuthenticationFilter;

  private AutoCloseable mocks;

  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private FilterChain filterChain;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    filterChain = mock(FilterChain.class);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
    SecurityContextHolder.clearContext();
  }

  @Test
  void doFilterInternalShouldAuthenticateValidToken() throws ServletException, IOException {
    String token = "valid.jwt.token";
    String username = "testuser";

    request.addHeader("Authorization", "Bearer " + token);

    when(jwtUtil.extractUsername(token)).thenReturn(username);
    when(jwtUtil.validateToken(token)).thenReturn(true);

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(username, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void doFilterInternalShouldNotAuthenticateInvalidToken() throws ServletException, IOException {
    String token = "invalid.jwt.token";

    request.addHeader("Authorization", "Bearer " + token);

    when(jwtUtil.extractUsername(token)).thenReturn("testuser");
    when(jwtUtil.validateToken(token)).thenReturn(false);

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void doFilterInternalShouldSkipAuthenticationWhenNoAuthorizationHeader()
      throws ServletException, IOException {
    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
    verify(jwtUtil, never()).extractUsername(anyString());
    verify(jwtUtil, never()).validateToken(anyString());
  }

  @Test
  void doFilterInternalShouldSkipAuthenticationWhenAuthorizationHeaderDoesNotStartWithBearer()
      throws ServletException, IOException {
    request.addHeader("Authorization", "Basic somebase64credentials");

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
    verify(jwtUtil, never()).extractUsername(anyString());
    verify(jwtUtil, never()).validateToken(anyString());
  }

  @Test
  void doFilterInternalShouldHandleExceptionWhenExtractingUsername()
      throws ServletException, IOException {
    String token = "malformed.token";

    request.addHeader("Authorization", "Bearer " + token);

    when(jwtUtil.extractUsername(token)).thenThrow(new RuntimeException("Invalid token"));

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void doFilterInternalShouldNotAuthenticateWhenUsernameIsNull()
      throws ServletException, IOException {
    String token = "token.without.username";

    request.addHeader("Authorization", "Bearer " + token);

    when(jwtUtil.extractUsername(token)).thenReturn(null);

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
    verify(jwtUtil, never()).validateToken(anyString());
  }

  @Test
  void doFilterInternalShouldNotOverrideExistingAuthentication()
      throws ServletException, IOException {
    String token = "valid.jwt.token";
    String username = "testuser";

    // Set up existing authentication
    org.springframework.security.authentication.UsernamePasswordAuthenticationToken existingAuth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "existinguser", null, null);
    SecurityContextHolder.getContext().setAuthentication(existingAuth);

    request.addHeader("Authorization", "Bearer " + token);

    when(jwtUtil.extractUsername(token)).thenReturn(username);
    when(jwtUtil.validateToken(token)).thenReturn(true);

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    // Should keep existing authentication
    assertEquals(
        "existinguser", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void doFilterInternalShouldExtractTokenCorrectlyFromBearerHeader()
      throws ServletException, IOException {
    String token = "my.jwt.token";
    String bearerToken = "Bearer " + token;

    request.addHeader("Authorization", bearerToken);

    when(jwtUtil.extractUsername(token)).thenReturn("testuser");
    when(jwtUtil.validateToken(token)).thenReturn(true);

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    verify(jwtUtil, times(1)).extractUsername(token);
    verify(jwtUtil, times(1)).validateToken(token);
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void doFilterInternalShouldAlwaysCallFilterChain() throws ServletException, IOException {
    // Test with no token
    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);
    verify(filterChain, times(1)).doFilter(request, response);

    // Reset
    reset(filterChain);

    // Test with invalid token
    request.addHeader("Authorization", "Bearer invalid.token");
    when(jwtUtil.extractUsername(anyString())).thenThrow(new RuntimeException("Invalid"));
    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);
    verify(filterChain, times(1)).doFilter(request, response);
  }
}

