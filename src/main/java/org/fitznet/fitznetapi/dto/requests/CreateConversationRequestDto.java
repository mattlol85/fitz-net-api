package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.fitznet.fitznetapi.dto.ConversationSettingsDto;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/** {@code POST /ai/conversations}: everything is optional. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateConversationRequestDto {

  public static final int MAX_TITLE_CHARS = 120;

  @Size(max = MAX_TITLE_CHARS, message = "title is too long")
  String title;

  @Valid ModelTargetDto target;

  @Valid ConversationSettingsDto settings;
}
