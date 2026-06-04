package org.fitznet.fitznetapi.config;

import java.util.HashMap;
import java.util.Map;
import org.fitznet.fitznetapi.metrics.FitzNetMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private final FitzNetMetrics fitzNetMetrics;

  public GlobalExceptionHandler(FitzNetMetrics fitzNetMetrics) {
    this.fitzNetMetrics = fitzNetMetrics;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handleResponseStatusException(
      ResponseStatusException ex) {
    fitzNetMetrics.recordApiFailure("response_status", Integer.toString(ex.getStatusCode().value()));

    Map<String, Object> errorResponse = new HashMap<>();
    errorResponse.put("success", false);
    errorResponse.put("message", ex.getReason());
    errorResponse.put("status", ex.getStatusCode().value());
    return new ResponseEntity<>(errorResponse, ex.getStatusCode());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidationExceptions(
      MethodArgumentNotValidException ex) {
    fitzNetMetrics.recordApiFailure("validation", Integer.toString(HttpStatus.BAD_REQUEST.value()));

    Map<String, Object> errorResponse = new HashMap<>();
    Map<String, String> errors = new HashMap<>();

    ex.getBindingResult()
        .getAllErrors()
        .forEach(
            (error) -> {
              String fieldName = ((FieldError) error).getField();
              String errorMessage = error.getDefaultMessage();
              errors.put(fieldName, errorMessage);
            });

    errorResponse.put("success", false);
    errorResponse.put("message", "Validation failed");
    errorResponse.put("errors", errors);
    errorResponse.put("status", HttpStatus.BAD_REQUEST.value());

    return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
    fitzNetMetrics.recordApiFailure("unexpected", Integer.toString(HttpStatus.INTERNAL_SERVER_ERROR.value()));

    Map<String, Object> errorResponse = new HashMap<>();
    errorResponse.put("success", false);
    errorResponse.put("message", "An unexpected error occurred: " + ex.getMessage());
    errorResponse.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
    return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
