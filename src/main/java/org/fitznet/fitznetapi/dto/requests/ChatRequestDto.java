package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A chat turn. Either {@code messages} (the full history including the new user turn) or a
 * single-turn {@code prompt} must be supplied; {@code messages} wins when both are present.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDto {
  public static final int MAX_MESSAGES = 200;

  String prompt;
  String model;

  @Valid
  @Size(max = MAX_MESSAGES, message = "too many messages")
  List<ChatMessageDto> messages;

  public ChatRequestDto(String prompt, String model) {
    this.prompt = prompt;
    this.model = model;
  }
}
