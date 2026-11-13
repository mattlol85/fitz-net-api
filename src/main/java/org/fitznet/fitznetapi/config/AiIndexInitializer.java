package org.fitznet.fitznetapi.config;

import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Creates the Matt-GPT indexes (contract section 12). Auto index creation is off in this API, so
 * they are ensured here at startup, like {@link UsernameIndexInitializer}. Failures are logged and
 * never stop startup. Later parts of the feature (attachments, presets) add their indexes here.
 */
@Slf4j
@Component
public class AiIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  @Autowired
  public AiIndexInitializer(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      ensureIndexes();
    } catch (RuntimeException e) {
      log.error("Matt-GPT index setup failed; startup continues and it will be retried on next start", e);
    }
  }

  void ensureIndexes() {
    mongoTemplate
        .indexOps(AiConversation.class)
        .createIndex(
            new Index()
                .on("ownerUsername", Sort.Direction.ASC)
                .on("pinned", Sort.Direction.DESC)
                .on("updatedAt", Sort.Direction.DESC)
                .named("owner_pinned_updated"));
    mongoTemplate
        .indexOps(AiMessage.class)
        .createIndex(
            new Index()
                .on("conversationId", Sort.Direction.ASC)
                .on("seq", Sort.Direction.ASC)
                .unique()
                .named("conversation_seq_unique"));
    mongoTemplate
        .indexOps(AiMessage.class)
        .createIndex(new Index().on("ownerUsername", Sort.Direction.ASC).named("owner"));
    log.info("Ensured Matt-GPT conversation and message indexes");
  }
}
