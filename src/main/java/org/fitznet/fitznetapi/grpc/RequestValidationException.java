package org.fitznet.fitznetapi.grpc;

import java.util.Map;
import java.util.stream.Collectors;

/** A gRPC request failed the same bean validation the REST body would; maps to INVALID_ARGUMENT. */
public class RequestValidationException extends RuntimeException {

  public RequestValidationException(Map<String, String> errors) {
    super(
        "Validation failed: "
            + errors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining("; ")));
  }
}
