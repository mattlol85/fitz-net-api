package org.fitznet.fitznetapi.repository;

import java.util.Optional;
import org.fitznet.fitznetapi.model.RecipeImage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.stereotype.Repository;

@Repository
@RepositoryRestResource(exported = false)
public interface RecipeImageRepository extends MongoRepository<RecipeImage, String> {

  Optional<RecipeImage> findByIdAndOwnerUsername(String id, String ownerUsername);

  long deleteByRecipeId(String recipeId);
}
