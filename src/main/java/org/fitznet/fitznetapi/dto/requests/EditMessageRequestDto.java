package org.fitznet.fitznetapi.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import java.util.List;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/**
 * {@code POST …/messages/{messageId}/edit}. {@code attachmentIds} absent keeps the message's
 * existing attachments; present (even empty) replaces them.
 */
public class EditMessageRequestDto {

  private String content;
  private List<String> attachmentIds;
  private boolean attachmentIdsPresent;

  @Valid private ModelTargetDto target;

  public EditMessageRequestDto() {}

  public EditMessageRequestDto(
      String content,
      boolean attachmentIdsPresent,
      List<String> attachmentIds,
      ModelTargetDto target) {
    this.content = content;
    this.attachmentIdsPresent = attachmentIdsPresent;
    this.attachmentIds = attachmentIds;
    this.target = target;
  }

  @JsonProperty("content")
  public String getContent() {
    return content;
  }

  @JsonProperty("content")
  public void setContent(String content) {
    this.content = content;
  }

  @JsonProperty("attachmentIds")
  public List<String> getAttachmentIds() {
    return attachmentIds;
  }

  @JsonSetter("attachmentIds")
  public void setAttachmentIds(List<String> attachmentIds) {
    this.attachmentIds = attachmentIds;
    this.attachmentIdsPresent = true;
  }

  @JsonIgnore
  public boolean isAttachmentIdsPresent() {
    return attachmentIdsPresent;
  }

  @JsonProperty("target")
  public ModelTargetDto getTarget() {
    return target;
  }

  @JsonProperty("target")
  public void setTarget(ModelTargetDto target) {
    this.target = target;
  }
}
