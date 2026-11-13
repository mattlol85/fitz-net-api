package org.fitznet.fitznetapi.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.fitznet.fitznetapi.model.AiConversation;

/** Owner-scoped list and search queries that need {@code MongoTemplate}. */
public interface AiConversationRepositoryCustom {

  /**
   * The owner's conversations, pinned first then {@code updatedAt} descending. {@code term}
   * (nullable) is a literal, case-insensitive substring matched against the title or any message
   * content; it is never interpreted as a regex.
   */
  List<AiConversation> search(String ownerUsername, String term, int limit, int offset);

  /**
   * For each of the conversations, the content of its first message (lowest seq) containing
   * {@code term} case-insensitively, in one query. Conversations without a hit are absent.
   */
  Map<String, String> firstMatchingContents(Collection<String> conversationIds, String term);
}
