package org.fitznet.fitznetapi.repository;

import org.fitznet.fitznetapi.model.PermissionDefinition;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PermissionRepository extends MongoRepository<PermissionDefinition, String> {}
