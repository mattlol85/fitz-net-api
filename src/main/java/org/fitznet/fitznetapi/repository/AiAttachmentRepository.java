package org.fitznet.fitznetapi.repository;

import java.util.Optional;
import org.fitznet.fitznetapi.model.AiAttachment;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiAttachmentRepository extends MongoRepository<AiAttachment, String> {

  Optional<AiAttachment> findByIdAndOwnerUsername(String id, String ownerUsername);

  long deleteByConversationId(String conversationId);

  long countByConversationId(String conversationId);
}
