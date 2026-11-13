package org.fitznet.fitznetapi.service.ai;

import java.util.List;
import java.util.Set;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.springframework.stereotype.Component;

/**
 * Deletes the attachments of deleted messages (regenerate and edit truncation) and of a deleted
 * conversation, so no image bytes outlive the message that carried them.
 */
@Component
public class AttachmentCleanupHook implements MessageLifecycleHook {

  private final AttachmentService attachments;

  public AttachmentCleanupHook(AttachmentService attachments) {
    this.attachments = attachments;
  }

  @Override
  public void onMessagesDeleted(AiConversation conversation, List<AiMessage> deleted) {
    attachments.deleteAll(AttachmentService.idsOf(deleted));
  }

  @Override
  public void onConversationDeleted(AiConversation conversation, List<AiMessage> messages) {
    Set<String> ids = AttachmentService.idsOf(messages);
    attachments.deleteForConversation(conversation.getId(), ids);
  }
}
