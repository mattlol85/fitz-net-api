package org.fitznet.fitznetapi.repository;

import org.fitznet.fitznetapi.model.AiUserPrefs;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiUserPrefsRepository extends MongoRepository<AiUserPrefs, String> {}
