package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "updatedPassword")
public class UpdateUserRequestDto {
  @NotBlank String username;
  String updatedUsername;
  String email;
  String updatedEmail;
  String updatedPassword;
}
