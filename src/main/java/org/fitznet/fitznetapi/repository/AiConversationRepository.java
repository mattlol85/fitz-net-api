package org.fitznet.fitznetapi.repository;

import java.util.Optional;
import org.fitznet.fitznetapi.model.AiConversation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiConversationRepository
    extends MongoRepository<AiConversation, String>, AiConversationRepositoryCustom {

  Optional<AiConversation> findByIdAndOwnerUsername(String id, String ownerUsername);
}
