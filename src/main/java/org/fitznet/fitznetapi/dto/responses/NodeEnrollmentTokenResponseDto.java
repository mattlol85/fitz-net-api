package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeEnrollmentTokenResponseDto {
  String token;
  Instant expiresAt;
}
