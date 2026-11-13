package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/**
 * {@code POST /ai/conversations/{id}/messages}. The browser sends only the new content; the server
 * builds the prompt from stored history. {@code content} may be empty only with an attachment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequestDto {

  String content;

  List<String> attachmentIds;

  /** Optional: when present it becomes the conversation's target before generating. */
  @Valid ModelTargetDto target;
}
