package org.fitznet.fitznetapi.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
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
  public Optional<String> firstMatchingContent(String conversationId, String term) {
    Query query =
        Query.query(
                Criteria.where("conversationId")
                    .is(conversationId)
                    .and("content")
                    .regex(literal(term), "i"))
            .with(Sort.by(Sort.Order.asc("seq")))
            .limit(1);
    AiMessage hit = mongoTemplate.findOne(query, AiMessage.class);
    return Optional.ofNullable(hit).map(AiMessage::getContent);
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
