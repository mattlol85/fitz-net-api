package org.fitznet.fitznetapi.config;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.model.Recipe;
import org.fitznet.fitznetapi.model.RecipeBook;
import org.fitznet.fitznetapi.model.RecipeImage;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Creates the Lori-Net recipe indexes. Auto index creation is off in this API, so they are ensured
 * here at startup, like {@code AiIndexInitializer}. Failures are logged and never stop startup.
 * {@code owner_unique} keeps one book per user; the image TTL index removes an upload that no recipe
 * ever claimed.
 */
@Slf4j
@Component
public class RecipeIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  public RecipeIndexInitializer(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      ensureIndexes();
    } catch (RuntimeException e) {
      log.error("Recipe index setup failed; startup continues and it will be retried on next start", e);
    }
  }

  void ensureIndexes() {
    mongoTemplate
        .indexOps(RecipeBook.class)
        .createIndex(
            new Index().on("ownerUsername", Sort.Direction.ASC).unique().named("owner_unique"));
    mongoTemplate
        .indexOps(Recipe.class)
        .createIndex(
            new Index()
                .on("ownerUsername", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
                .named("owner_created"));
    mongoTemplate
        .indexOps(Recipe.class)
        .createIndex(
            new Index()
                .on("ownerUsername", Sort.Direction.ASC)
                .on("ingredientTerms", Sort.Direction.ASC)
                .named("owner_ingredient_terms"));
    mongoTemplate
        .indexOps(Recipe.class)
        .createIndex(
            new Index()
                .on("ownerUsername", Sort.Direction.ASC)
                .on("chapterId", Sort.Direction.ASC)
                .named("owner_chapter"));
    mongoTemplate
        .indexOps(RecipeImage.class)
        .createIndex(
            new Index()
                .on("expiresAt", Sort.Direction.ASC)
                .expire(0, TimeUnit.SECONDS)
                .named("expires_at_ttl"));
    mongoTemplate
        .indexOps(RecipeImage.class)
        .createIndex(new Index().on("recipeId", Sort.Direction.ASC).named("recipe"));
    log.info("Ensured recipe book, recipe and recipe image indexes");
  }
}
