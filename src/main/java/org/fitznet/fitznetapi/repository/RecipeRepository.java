package org.fitznet.fitznetapi.repository;

import java.util.Optional;
import org.fitznet.fitznetapi.model.Recipe;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.stereotype.Repository;

@Repository
@RepositoryRestResource(exported = false)
public interface RecipeRepository extends MongoRepository<Recipe, String>, RecipeRepositoryCustom {

  Optional<Recipe> findByIdAndOwnerUsername(String id, String ownerUsername);
}
