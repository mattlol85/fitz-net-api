package org.fitznet.fitznetapi.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileResponseDto {
  boolean success;
  String message;
  String username;
  String email;
  @JsonProperty("boardColor") String boardColor;
}

