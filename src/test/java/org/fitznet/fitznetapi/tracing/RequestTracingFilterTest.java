package org.fitznet.fitznetapi.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTracingFilterTest {

  private final RequestTracingFilter filter = new RequestTracingFilter();

  @Test
  void reusesInboundCorrelationIdAndExposesItInMdcAndResponse() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/info");
    request.addHeader(TraceContext.CORRELATION_ID_HEADER, "web-1a2b");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seen = new AtomicReference<>();
    FilterChain chain = (req, res) -> seen.set(MDC.get(TraceContext.MDC_CORRELATION_ID));

    filter.doFilter(request, response, chain);

    assertEquals("web-1a2b", seen.get());
    assertEquals("web-1a2b", response.getHeader(TraceContext.CORRELATION_ID_HEADER));
    assertNotNull(response.getHeader(TraceContext.REQUEST_ID_HEADER));
    assertNull(MDC.get(TraceContext.MDC_CORRELATION_ID));
  }

  @Test
  void generatesIdsWhenMissingAndDefaultsCorrelationToRequestId() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/info"), response, (req, res) -> {});

    assertEquals(
        response.getHeader(TraceContext.REQUEST_ID_HEADER),
        response.getHeader(TraceContext.CORRELATION_ID_HEADER));
  }

  @Test
  void rejectsUnsafeInboundIds() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/info");
    request.addHeader(TraceContext.CORRELATION_ID_HEADER, "bad id\nforged log line");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    String id = response.getHeader(TraceContext.CORRELATION_ID_HEADER);
    assertEquals(8, id.length());
  }
}
