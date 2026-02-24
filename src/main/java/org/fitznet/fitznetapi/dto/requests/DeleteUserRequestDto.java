package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DeleteUserRequestDto {
  @NotBlank String username;
}
