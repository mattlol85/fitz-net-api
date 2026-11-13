package org.fitznet.fitznetapi.repository;

import java.util.List;
import org.fitznet.fitznetapi.model.AiPreset;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiPresetRepository extends MongoRepository<AiPreset, String> {

  List<AiPreset> findByOwnerUsernameAndVisibility(String ownerUsername, String visibility);

  List<AiPreset> findByVisibility(String visibility);
}
