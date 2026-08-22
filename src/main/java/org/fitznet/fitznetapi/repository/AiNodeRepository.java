package org.fitznet.fitznetapi.repository;

import org.fitznet.fitznetapi.model.AiNode;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiNodeRepository extends MongoRepository<AiNode, String> {}
