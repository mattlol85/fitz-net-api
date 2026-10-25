package org.fitznet.fitznetapi.tracing;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;
import org.springframework.stereotype.Component;

/**
 * gRPC counterpart of {@link RequestTracingFilter}. Reads {@code x-correlation-id} /
 * {@code x-request-id} metadata (or generates ids), keeps them in the MDC for every listener
 * callback, echoes them in the response headers and logs one line per call with its status.
 */
@Component
@GlobalServerInterceptor
@Order(GrpcExceptionHandlerInterceptor.ORDER - 10)
public class GrpcTracingInterceptor implements ServerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(GrpcTracingInterceptor.class);

  static final Metadata.Key<String> REQUEST_ID =
      Metadata.Key.of(
          TraceContext.REQUEST_ID_HEADER.toLowerCase(), Metadata.ASCII_STRING_MARSHALLER);
  static final Metadata.Key<String> CORRELATION_ID =
      Metadata.Key.of(
          TraceContext.CORRELATION_ID_HEADER.toLowerCase(), Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String requestId = TraceContext.idOrNew(headers.get(REQUEST_ID));
    String correlationId = TraceContext.idOrDefault(headers.get(CORRELATION_ID), requestId);
    String method = call.getMethodDescriptor().getFullMethodName();
    long start = System.currentTimeMillis();

    ServerCall<ReqT, RespT> tracedCall =
        new SimpleForwardingServerCall<>(call) {
          @Override
          public void sendHeaders(Metadata responseHeaders) {
            responseHeaders.put(REQUEST_ID, requestId);
            responseHeaders.put(CORRELATION_ID, correlationId);
            super.sendHeaders(responseHeaders);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            TraceContext.put(requestId, correlationId, "grpc");
            try {
              log.info(
                  "gRPC {} -> {} ({}ms)",
                  method,
                  status.getCode(),
                  System.currentTimeMillis() - start);
            } finally {
              TraceContext.clear();
            }
            super.close(status, trailers);
          }
        };

    TraceContext.put(requestId, correlationId, "grpc");
    try {
      return new TracingListener<>(next.startCall(tracedCall, headers), requestId, correlationId);
    } finally {
      TraceContext.clear();
    }
  }

  /** Re-establishes the MDC around every callback, which may run on a different thread. */
  private static final class TracingListener<ReqT>
      extends SimpleForwardingServerCallListener<ReqT> {
    private final String requestId;
    private final String correlationId;

    TracingListener(ServerCall.Listener<ReqT> delegate, String requestId, String correlationId) {
      super(delegate);
      this.requestId = requestId;
      this.correlationId = correlationId;
    }

    @Override
    public void onMessage(ReqT message) {
      TraceContext.put(requestId, correlationId, "grpc");
      try {
        super.onMessage(message);
      } finally {
        TraceContext.clear();
      }
    }

    @Override
    public void onHalfClose() {
      TraceContext.put(requestId, correlationId, "grpc");
      try {
        super.onHalfClose();
      } finally {
        TraceContext.clear();
      }
    }

    @Override
    public void onCancel() {
      TraceContext.put(requestId, correlationId, "grpc");
      try {
        super.onCancel();
      } finally {
        TraceContext.clear();
      }
    }

    @Override
    public void onComplete() {
      TraceContext.put(requestId, correlationId, "grpc");
      try {
        super.onComplete();
      } finally {
        TraceContext.clear();
      }
    }
  }
}
