package org.fitznet.fitznetapi.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;

@Data
@NoArgsConstructor
public class UserDTO {
  private String username;
  private String email;
  private String password;

  public UserDTO(String username, String email, String password) {
    this.username = username;
    this.email = email;
    this.password = password;

    sanitizeUsernameAndEmail();
  }

  public void sanitizeUsernameAndEmail() {
    this.username = this.username.toLowerCase(Locale.ROOT);
    this.email = this.email.toLowerCase(Locale.ROOT);
  }
}
