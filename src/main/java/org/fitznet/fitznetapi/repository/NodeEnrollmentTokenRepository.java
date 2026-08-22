package org.fitznet.fitznetapi.repository;

import org.fitznet.fitznetapi.model.NodeEnrollmentToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NodeEnrollmentTokenRepository extends MongoRepository<NodeEnrollmentToken, String> {}
