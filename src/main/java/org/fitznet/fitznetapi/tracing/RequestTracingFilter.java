package org.fitznet.fitznetapi.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every HTTP request a request id and correlation id (reusing the caller's when sent),
 * puts them in the MDC for the whole request, echoes them on the response, and logs one summary
 * line per request. Runs before Spring Security so rejected requests are traced too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTracingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestTracingFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = TraceContext.idOrNew(request.getHeader(TraceContext.REQUEST_ID_HEADER));
    String correlationId =
        TraceContext.idOrDefault(request.getHeader(TraceContext.CORRELATION_ID_HEADER), requestId);
    TraceContext.put(requestId, correlationId, "http");
    response.setHeader(TraceContext.REQUEST_ID_HEADER, requestId);
    response.setHeader(TraceContext.CORRELATION_ID_HEADER, correlationId);

    long start = System.currentTimeMillis();
    try {
      chain.doFilter(request, response);
    } finally {
      log.info(
          "{} {} -> {} ({}ms)",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          System.currentTimeMillis() - start);
      TraceContext.clear();
    }
  }
}
