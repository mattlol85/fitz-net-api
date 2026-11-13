package org.fitznet.fitznetapi.service.ai;

import java.util.List;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner scoping for conversations: a conversation (or message) owned by someone else answers 404,
 * never 403, so its existence is not leaked. ADMIN gets no access to other users' conversations.
 */
@Component
public class ConversationAccess {

  private final AiConversationRepository conversations;
  private final AiMessageRepository messages;

  public ConversationAccess(AiConversationRepository conversations, AiMessageRepository messages) {
    this.conversations = conversations;
    this.messages = messages;
  }

  public AiConversation owned(String username, String conversationId) {
    return conversations
        .findByIdAndOwnerUsername(conversationId, username)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
  }

  /** The stored messages of a conversation in {@code seq} order. */
  public List<AiMessage> history(String conversationId) {
    return messages.findByConversationIdOrderBySeqAsc(conversationId);
  }

  public AiMessage message(AiConversation conversation, String messageId) {
    return messages
        .findByIdAndConversationId(messageId, conversation.getId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
  }
}
