package org.fitznet.fitznetapi.dto.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponseDto {
  private String id;
  private String username;
  private String email;

  // Factory method to create from User entity
  public static UserResponseDto fromUser(org.fitznet.fitznetapi.model.User user) {
    if (user == null) {
      return null;
    }
    return UserResponseDto.builder()
        .id(user.getId())
        .username(user.getUsername())
        .email(user.getEmail())
        .build();
  }
}

