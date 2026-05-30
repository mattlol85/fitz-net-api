package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForgotPasswordRequestDto {

  @NotBlank
  @Email
  private String email;
}
