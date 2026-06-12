package org.fitznet.fitznetapi.dto.requests;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "updatedPassword")
public class UpdateUserRequestDto {
  // Always overwritten server-side with the authenticated principal; never client-supplied.
  String username;
  String updatedUsername;
  String email;
  String updatedEmail;
  String updatedPassword;
}
