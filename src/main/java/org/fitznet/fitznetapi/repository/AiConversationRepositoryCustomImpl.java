package org.fitznet.fitznetapi.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.springframework.data.domain.Sort;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

public class AiConversationRepositoryCustomImpl implements AiConversationRepositoryCustom {

  private static final Pattern REGEX_META = Pattern.compile("[\\\\.^$|?*+()\\[\\]{}]");

  private final MongoTemplate mongoTemplate;

  public AiConversationRepositoryCustomImpl(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public List<AiConversation> search(String ownerUsername, String term, int limit, int offset) {
    Criteria criteria = Criteria.where("ownerUsername").is(ownerUsername);
    if (term != null && !term.isBlank()) {
      String literal = literal(term);
      List<String> idsWithHit =
          mongoTemplate.findDistinct(
              Query.query(
                  Criteria.where("ownerUsername")
                      .is(ownerUsername)
                      .and("content")
                      .regex(literal, "i")),
              "conversationId",
              AiMessage.class,
              String.class);
      List<Criteria> either = new ArrayList<>();
      either.add(Criteria.where("title").regex(literal, "i"));
      if (!idsWithHit.isEmpty()) {
        either.add(Criteria.where("_id").in(idsWithHit.stream().map(this::objectId).toList()));
      }
      criteria = new Criteria().andOperator(criteria, new Criteria().orOperator(either));
    }
    Query query =
        Query.query(criteria)
            .with(
                Sort.by(
                    Sort.Order.desc("pinned"), Sort.Order.desc("updatedAt"), Sort.Order.desc("_id")))
            .skip(offset)
            .limit(limit);
    return mongoTemplate.find(query, AiConversation.class);
  }

  @Override
  public Map<String, String> firstMatchingContents(Collection<String> conversationIds, String term) {
    if (conversationIds.isEmpty()) {
      return Map.of();
    }
    Aggregation aggregation =
        Aggregation.newAggregation(
            Aggregation.match(
                Criteria.where("conversationId").in(conversationIds).and("content").regex(literal(term), "i")),
            Aggregation.sort(Sort.Direction.ASC, "seq"),
            Aggregation.group("conversationId").first("content").as("content"));
    Map<String, String> firstHits = new HashMap<>();
    for (Document row : mongoTemplate.aggregate(aggregation, AiMessage.class, Document.class)) {
      firstHits.put(row.getString("_id"), row.getString("content"));
    }
    return firstHits;
  }

  private Object objectId(String id) {
    try {
      return new ObjectId(id);
    } catch (IllegalArgumentException ex) {
      return id;
    }
  }

  /** Escapes every regex metacharacter so the term matches literally. */
  static String literal(String term) {
    return REGEX_META.matcher(term).replaceAll("\\\\$0");
  }
}
