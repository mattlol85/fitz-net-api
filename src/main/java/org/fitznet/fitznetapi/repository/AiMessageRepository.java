package org.fitznet.fitznetapi.repository;

import java.util.List;
import java.util.Optional;
import org.fitznet.fitznetapi.model.AiMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiMessageRepository extends MongoRepository<AiMessage, String> {

  List<AiMessage> findByConversationIdOrderBySeqAsc(String conversationId);

  Optional<AiMessage> findByIdAndConversationId(String id, String conversationId);

  long deleteByConversationId(String conversationId);

  /** Deletes the message at {@code seq} and everything after it. */
  long deleteByConversationIdAndSeqGreaterThanEqual(String conversationId, int seq);

  long countByConversationId(String conversationId);
}
