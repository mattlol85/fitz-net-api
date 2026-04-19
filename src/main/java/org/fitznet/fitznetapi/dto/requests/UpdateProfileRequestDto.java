package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "password")
public class UpdateProfileRequestDto {
  @NotBlank String username;
  @NotBlank @Email String email;
  String password;
}

