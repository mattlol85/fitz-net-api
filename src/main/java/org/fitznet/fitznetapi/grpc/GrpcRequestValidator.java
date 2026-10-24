package org.fitznet.fitznetapi.grpc;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Runs the DTO constraints that {@code @Valid @RequestBody} enforces in MVC, since gRPC services
 * call the controllers directly and skip argument binding.
 */
@Component
public class GrpcRequestValidator {

  private final Validator validator;

  public GrpcRequestValidator(Validator validator) {
    this.validator = validator;
  }

  public <T> T valid(T dto) {
    Set<ConstraintViolation<T>> violations = validator.validate(dto);
    if (!violations.isEmpty()) {
      Map<String, String> errors = new LinkedHashMap<>();
      violations.forEach(v -> errors.put(v.getPropertyPath().toString(), v.getMessage()));
      throw new RequestValidationException(errors);
    }
    return dto;
  }

  /** For REST path variables / headers, which are required and never blank. */
  public String required(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new RequestValidationException(Map.of(field, "must not be blank"));
    }
    return value;
  }
}
