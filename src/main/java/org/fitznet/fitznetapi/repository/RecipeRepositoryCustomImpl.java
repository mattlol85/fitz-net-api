package org.fitznet.fitznetapi.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.fitznet.fitznetapi.model.Recipe;
import org.fitznet.fitznetapi.util.MongoRegex;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class RecipeRepositoryCustomImpl implements RecipeRepositoryCustom {

  private final MongoTemplate mongoTemplate;

  public RecipeRepositoryCustomImpl(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public List<Recipe> search(
      String ownerUsername, List<String> terms, String titleQuery, String chapterId, int limit) {
    Query query =
        Query.query(criteria(ownerUsername, terms, titleQuery, chapterId))
            .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
            .limit(limit);
    query.fields().exclude("ingredients", "instructions", "ingredientTerms");
    return mongoTemplate.find(query, Recipe.class);
  }

  @Override
  public long countMatching(
      String ownerUsername, List<String> terms, String titleQuery, String chapterId) {
    return mongoTemplate.count(
        Query.query(criteria(ownerUsername, terms, titleQuery, chapterId)), Recipe.class);
  }

  private static Criteria criteria(
      String ownerUsername, List<String> terms, String titleQuery, String chapterId) {
    Criteria criteria = Criteria.where("ownerUsername").is(ownerUsername);
    if (terms != null && !terms.isEmpty()) {
      criteria = criteria.and("ingredientTerms").all(terms);
    }
    if (titleQuery != null && !titleQuery.isBlank()) {
      criteria = criteria.and("title").regex(MongoRegex.literal(titleQuery), "i");
    }
    if (chapterId != null) {
      criteria = criteria.and("chapterId").is(chapterId);
    }
    return criteria;
  }

  @Override
  public List<IndexRow> ingredientIndex(String ownerUsername) {
    Aggregation aggregation =
        Aggregation.newAggregation(
            Aggregation.match(Criteria.where("ownerUsername").is(ownerUsername)),
            Aggregation.project("title", "ingredientNames"),
            Aggregation.unwind("ingredientNames"),
            Aggregation.group("ingredientNames")
                .addToSet(new Document("id", "$_id").append("title", "$title"))
                .as("recipes"),
            Aggregation.sort(Sort.Direction.ASC, "_id"));
    List<IndexRow> rows = new ArrayList<>();
    for (Document row : mongoTemplate.aggregate(aggregation, Recipe.class, Document.class)) {
      List<RecipeRef> recipes = new ArrayList<>();
      for (Document ref : row.getList("recipes", Document.class)) {
        recipes.add(new RecipeRef(ref.get("id").toString(), ref.getString("title")));
      }
      recipes.sort(
          Comparator.comparing((RecipeRef r) -> r.title() == null ? "" : r.title().toLowerCase())
              .thenComparing(RecipeRef::id));
      rows.add(new IndexRow(row.getString("_id"), recipes));
    }
    return rows;
  }

  @Override
  public Map<String, Long> countByChapter(String ownerUsername) {
    Aggregation aggregation =
        Aggregation.newAggregation(
            Aggregation.match(Criteria.where("ownerUsername").is(ownerUsername)),
            Aggregation.group("chapterId").count().as("count"));
    Map<String, Long> counts = new HashMap<>();
    for (Document row : mongoTemplate.aggregate(aggregation, Recipe.class, Document.class)) {
      Object id = row.get("_id");
      counts.put(id == null ? null : id.toString(), ((Number) row.get("count")).longValue());
    }
    return counts;
  }

  @Override
  public long unsetChapter(String ownerUsername, Collection<String> chapterIds) {
    if (chapterIds.isEmpty()) {
      return 0;
    }
    return mongoTemplate
        .updateMulti(
            Query.query(
                Criteria.where("ownerUsername").is(ownerUsername).and("chapterId").in(chapterIds)),
            new Update().unset("chapterId"),
            Recipe.class)
        .getModifiedCount();
  }
}
