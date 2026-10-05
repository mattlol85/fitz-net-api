package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePermissionRequestDto {
  @NotBlank
  @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "Use UPPER_SNAKE_CASE, 2-32 characters")
  String name;

  @Size(max = 200)
  String description;
}
