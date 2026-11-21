package org.fitznet.fitznetapi.repository;

import java.util.Optional;
import org.fitznet.fitznetapi.model.RecipeBook;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.stereotype.Repository;

@Repository
@RepositoryRestResource(exported = false)
public interface RecipeBookRepository extends MongoRepository<RecipeBook, String> {

  Optional<RecipeBook> findByOwnerUsername(String ownerUsername);
}
