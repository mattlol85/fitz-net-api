package org.fitznet.fitznetapi.repository;

import java.util.List;
import java.util.Optional;
import org.fitznet.fitznetapi.model.AiConversation;

/** Owner-scoped list and search queries that need {@code MongoTemplate}. */
public interface AiConversationRepositoryCustom {

  /**
   * The owner's conversations, pinned first then {@code updatedAt} descending. {@code term}
   * (nullable) is a literal, case-insensitive substring matched against the title or any message
   * content; it is never interpreted as a regex.
   */
  List<AiConversation> search(String ownerUsername, String term, int limit, int offset);

  /** Content of the first message (lowest seq) containing {@code term}, case-insensitively. */
  Optional<String> firstMatchingContent(String conversationId, String term);
}
