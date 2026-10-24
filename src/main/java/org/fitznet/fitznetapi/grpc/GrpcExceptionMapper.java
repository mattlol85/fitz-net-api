package org.fitznet.fitznetapi.grpc;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.fitznet.fitznetapi.metrics.FitzNetMetrics;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * gRPC counterpart of {@code GlobalExceptionHandler}: same messages and failure metrics, with the
 * HTTP status translated to a gRPC code. The REST status is also sent as the {@code x-http-status}
 * trailer for clients that want the exact equivalent.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GrpcExceptionMapper implements GrpcExceptionHandler {

  static final Metadata.Key<String> HTTP_STATUS =
      Metadata.Key.of("x-http-status", Metadata.ASCII_STRING_MARSHALLER);

  private final FitzNetMetrics fitzNetMetrics;

  public GrpcExceptionMapper(FitzNetMetrics fitzNetMetrics) {
    this.fitzNetMetrics = fitzNetMetrics;
  }

  @Override
  public StatusException handleException(Throwable ex) {
    if (ex instanceof StatusException || ex instanceof StatusRuntimeException) {
      return null; // already a gRPC status; let the default handling pass it through
    }
    if (ex instanceof ResponseStatusException rse) {
      return error("response_status", rse.getStatusCode().value(), rse.getReason());
    }
    if (ex instanceof RequestValidationException) {
      return error("validation", HttpStatus.BAD_REQUEST.value(), ex.getMessage());
    }
    if (ex instanceof AuthenticationException) {
      return error("authentication", HttpStatus.UNAUTHORIZED.value(), "Unauthorized");
    }
    if (ex instanceof AccessDeniedException) {
      return error("access_denied", HttpStatus.FORBIDDEN.value(), "Forbidden");
    }
    if (ex instanceof DuplicateKeyException) {
      return error("conflict", HttpStatus.CONFLICT.value(), "Username already taken");
    }
    return error(
        "unexpected",
        HttpStatus.INTERNAL_SERVER_ERROR.value(),
        "An unexpected error occurred: " + ex.getMessage());
  }

  private StatusException error(String type, int httpStatus, String message) {
    fitzNetMetrics.recordApiFailure(type, Integer.toString(httpStatus));
    Metadata trailers = new Metadata();
    trailers.put(HTTP_STATUS, Integer.toString(httpStatus));
    return toGrpcStatus(httpStatus).withDescription(message).asException(trailers);
  }

  static Status toGrpcStatus(int httpStatus) {
    return switch (httpStatus) {
      case 400 -> Status.INVALID_ARGUMENT;
      case 401 -> Status.UNAUTHENTICATED;
      case 403 -> Status.PERMISSION_DENIED;
      case 404 -> Status.NOT_FOUND;
      case 409 -> Status.ALREADY_EXISTS;
      case 412 -> Status.FAILED_PRECONDITION;
      case 429 -> Status.RESOURCE_EXHAUSTED;
      case 501 -> Status.UNIMPLEMENTED;
      case 502, 503 -> Status.UNAVAILABLE;
      case 504 -> Status.DEADLINE_EXCEEDED;
      default -> httpStatus >= 500 ? Status.INTERNAL : Status.UNKNOWN;
    };
  }
}
