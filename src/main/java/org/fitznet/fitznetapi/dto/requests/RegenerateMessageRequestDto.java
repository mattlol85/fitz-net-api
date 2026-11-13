package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/** {@code POST …/messages/{messageId}/regenerate}; the whole body is optional. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegenerateMessageRequestDto {

  /** Optional: "Regenerate with…" and the failover action. */
  @Valid ModelTargetDto target;
}
