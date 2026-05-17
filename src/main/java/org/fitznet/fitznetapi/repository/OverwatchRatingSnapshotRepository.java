package org.fitznet.fitznetapi.repository;

import java.util.List;
import org.fitznet.fitznetapi.model.OverwatchRatingSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OverwatchRatingSnapshotRepository extends MongoRepository<OverwatchRatingSnapshot, String> {

  List<OverwatchRatingSnapshot> findByUserIdAndSeasonOrderByRecordedAtAsc(String userId, String season);

  List<OverwatchRatingSnapshot> findByUserIdOrderByRecordedAtAsc(String userId);
}
