package org.fitznet.fitznetapi.service.ai;

import java.util.List;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;

/**
 * Extension point for things that hang off messages and must follow their deletion: attachments
 * (part 3) are removed with the messages that carried them. Register an implementation as a Spring
 * bean; {@link ConversationService} and {@link TurnService} call every registered hook.
 */
public interface MessageLifecycleHook {

  /**
   * Messages were deleted by a regenerate, an edit or an edit's truncation. For an edit the edited
   * message itself is not in the list: it stays, with its attachments replaced if the request said
   * so.
   */
  default void onMessagesDeleted(AiConversation conversation, List<AiMessage> deleted) {}

  /** The whole conversation and all its messages are about to be deleted. */
  default void onConversationDeleted(AiConversation conversation, List<AiMessage> messages) {}
}
