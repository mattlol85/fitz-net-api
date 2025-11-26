package org.fitznet.fitznetapi.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.responses.ErrorResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponseDto> handleResponseStatusException(
      ResponseStatusException ex, HttpServletRequest request) {
    log.error("ResponseStatusException: {} - {}", ex.getStatusCode(), ex.getReason());

    ErrorResponseDto error = ErrorResponseDto.builder()
        .status(ex.getStatusCode().value())
        .error(ex.getStatusCode().toString())
        .message(ex.getReason())
        .path(request.getRequestURI())
        .timestamp(System.currentTimeMillis())
        .build();

    return ResponseEntity.status(ex.getStatusCode()).body(error);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponseDto> handleValidationException(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    log.error("Validation error: {}", ex.getMessage());

    String validationErrors = ex.getBindingResult()
        .getFieldErrors()
        .stream()
        .map(error -> error.getField() + ": " + error.getDefaultMessage())
        .collect(Collectors.joining(", "));

    ErrorResponseDto error = ErrorResponseDto.builder()
        .status(HttpStatus.BAD_REQUEST.value())
        .error("Validation Failed")
        .message(validationErrors)
        .path(request.getRequestURI())
        .timestamp(System.currentTimeMillis())
        .build();

    return ResponseEntity.badRequest().body(error);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponseDto> handleGenericException(
      Exception ex, HttpServletRequest request) {
    log.error("Unexpected error: ", ex);

    ErrorResponseDto error = ErrorResponseDto.builder()
        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
        .error("Internal Server Error")
        .message("An unexpected error occurred")
        .path(request.getRequestURI())
        .timestamp(System.currentTimeMillis())
        .build();

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
  }
}

