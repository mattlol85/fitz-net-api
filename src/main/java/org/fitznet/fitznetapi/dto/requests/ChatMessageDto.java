package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One turn of a conversation, as replayed to the model on every request. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {
  @NotBlank
  @Pattern(regexp = "user|assistant", message = "role must be 'user' or 'assistant'")
  String role;

  @NotBlank String content;
}
