package org.fitznet.fitznetapi.service.ai;

import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.StoredSummary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Targeted ({@code $set} / {@code $unset}) writes to a conversation document. Several writers touch
 * the same conversation at different times: the turn pipeline (counters, leaf, target, summary),
 * PATCH, title generation and summarise (both of which wait on a model for a long time). A
 * whole-document {@code save} of a document loaded earlier would silently rewind another writer's
 * fields (a stale {@code nextSeq} makes the next send collide on the unique message index), so
 * everything outside {@code AiConversationRepository.save} on a fresh document goes through here and
 * writes only the fields it owns.
 */
@Component
public class ConversationWrites {

  private final MongoTemplate mongo;

  public ConversationWrites(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  /** Applies the update to the conversation; an update with no operations is skipped. */
  public void apply(String conversationId, Update update) {
    if (update.getUpdateObject().isEmpty()) {
      return;
    }
    mongo.updateFirst(byId(conversationId), update, AiConversation.class);
  }

  /**
   * Stores a generated title, but only while the user has not set one: a rename that landed while
   * the model was thinking wins. Returns whether the title was written.
   */
  public boolean applyAutoTitle(String conversationId, String title) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(conversationId)
                .and("titleSource")
                .ne(AiConversation.TITLE_USER));
    Update update =
        new Update()
            .set("title", title)
            .set("titleSource", AiConversation.TITLE_AUTO);
    return mongo.updateFirst(query, update, AiConversation.class).getModifiedCount() > 0;
  }

  /** The provisional title of a first message, only while the title is still the default one. */
  public void applyProvisionalTitle(String conversationId, String title) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(conversationId)
                .and("titleSource")
                .is(AiConversation.TITLE_DEFAULT));
    mongo.updateFirst(query, new Update().set("title", title), AiConversation.class);
  }

  public void setSummary(String conversationId, StoredSummary summary) {
    apply(conversationId, new Update().set("summary", summary));
  }

  private static Query byId(String conversationId) {
    return Query.query(Criteria.where("_id").is(conversationId));
  }
}
