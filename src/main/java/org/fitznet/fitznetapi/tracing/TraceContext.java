package org.fitznet.fitznetapi.tracing;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Shared names and helpers for end-to-end request tracing. The same correlation id travels the
 * whole chain (browser -> REST/gRPC/STOMP -> logs) as {@code X-Correlation-ID} (HTTP, STOMP) or
 * {@code x-correlation-id} (gRPC metadata) and is exposed to every log line as the MDC key
 * {@code correlationId}.
 */
public final class TraceContext {

  public static final String REQUEST_ID_HEADER = "X-Request-ID";
  public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

  public static final String MDC_REQUEST_ID = "requestId";
  public static final String MDC_CORRELATION_ID = "correlationId";
  public static final String MDC_TRANSPORT = "transport";

  /** Inbound ids are echoed into logs and headers, so only a conservative charset is accepted. */
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

  private TraceContext() {}

  /** Returns the inbound id if it is well formed, otherwise a fresh short id. */
  public static String idOrNew(String inbound) {
    return idOrDefault(inbound, newId());
  }

  /** Returns the inbound id if it is well formed, otherwise {@code fallback}. */
  public static String idOrDefault(String inbound, String fallback) {
    return inbound != null && SAFE_ID.matcher(inbound).matches() ? inbound : fallback;
  }

  public static String newId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  public static void put(String requestId, String correlationId, String transport) {
    MDC.put(MDC_REQUEST_ID, requestId);
    MDC.put(MDC_CORRELATION_ID, correlationId);
    MDC.put(MDC_TRANSPORT, transport);
  }

  public static void clear() {
    MDC.remove(MDC_REQUEST_ID);
    MDC.remove(MDC_CORRELATION_ID);
    MDC.remove(MDC_TRANSPORT);
  }
}
