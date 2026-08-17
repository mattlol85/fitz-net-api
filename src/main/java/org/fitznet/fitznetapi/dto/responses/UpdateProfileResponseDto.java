package org.fitznet.fitznetapi.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileResponseDto {
  @JsonProperty("success") boolean success;
  @JsonProperty("message") String message;
  @JsonProperty("username") String username;
  @JsonProperty("email") String email;
  @JsonProperty("boardColor") String boardColor;
}

