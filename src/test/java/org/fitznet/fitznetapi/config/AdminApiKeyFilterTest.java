package org.fitznet.fitznetapi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminApiKeyFilterTest {

  private final FilterChain chain = (req, res) -> {};

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void run(String configured, String provided) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (provided != null) {
      request.addHeader(AdminApiKeyFilter.HEADER, provided);
    }
    new AdminApiKeyFilter(configured)
        .doFilterInternal(request, new MockHttpServletResponse(), chain);
  }

  @Test
  void validKeyAuthenticatesAsAdmin() throws Exception {
    run("secret", "secret");
    var auth = SecurityContextHolder.getContext().getAuthentication();
    assertEquals(AdminApiKeyFilter.PRINCIPAL, auth.getName());
    assertEquals("PERM_ADMIN", auth.getAuthorities().iterator().next().getAuthority());
  }

  @Test
  void wrongKeyDoesNotAuthenticate() throws Exception {
    run("secret", "other");
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void unsetKeyDisablesThePattern() throws Exception {
    run("", "");
    assertNull(SecurityContextHolder.getContext().getAuthentication());
    run(null, "anything");
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }
}
