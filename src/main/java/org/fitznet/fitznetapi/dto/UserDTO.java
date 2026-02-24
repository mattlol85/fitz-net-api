package org.fitznet.fitznetapi.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import lombok.ToString;
import lombok.Value;

@Value
@ToString(exclude = "password")
public class UserDTO {
  @NotBlank String username;
  @Email String email;
  @NotBlank String password;

  @JsonCreator
  public UserDTO(
      @JsonProperty("username") String username,
      @JsonProperty("email") String email,
      @JsonProperty("password") String password) {
    this.username = normalize(username);
    this.email = normalize(email);
    this.password = password;
  }

  private static String normalize(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }
}
