package org.fitznet.fitznetapi.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.util.UsernameNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Startup migration that makes usernames case-insensitive at the storage level.
 *
 * <ul>
 *   <li>Legacy mixed-case usernames that have no collision are lowercased (idempotent).
 *   <li>Usernames that collide after normalization are logged and left untouched; nothing is
 *       merged or deleted.
 *   <li>The unique {@code username_unique} index is created only when there are no collisions.
 *   <li>Failures are logged and never stop startup.
 * </ul>
 */
@Slf4j
@Component
public class UsernameIndexInitializer implements ApplicationRunner {

  static final String INDEX_NAME = "username_unique";

  private final MongoTemplate mongoTemplate;

  @Autowired
  public UsernameIndexInitializer(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      migrate();
    } catch (RuntimeException e) {
      log.error(
          "Username migration or index setup failed; startup continues and it will be retried on next start",
          e);
    }
  }

  void migrate() {
    List<User> users = mongoTemplate.findAll(User.class);

    Map<String, List<User>> byNormalized = new LinkedHashMap<>();
    List<User> withoutUsername = new ArrayList<>();
    for (User user : users) {
      if (user.getUsername() == null) {
        withoutUsername.add(user);
      } else {
        byNormalized
            .computeIfAbsent(UsernameNormalizer.normalize(user.getUsername()), k -> new ArrayList<>())
            .add(user);
      }
    }

    boolean hasCollisions = false;

    if (!withoutUsername.isEmpty()) {
      hasCollisions = true;
      log.error(
          "Found {} user(s) with no username; they block the unique index. ids={}",
          withoutUsername.size(),
          withoutUsername.stream().map(User::getId).toList());
    }

    for (Map.Entry<String, List<User>> entry : byNormalized.entrySet()) {
      List<User> group = entry.getValue();
      if (group.size() == 1) {
        lowercaseIfNeeded(group.get(0), entry.getKey());
      } else {
        hasCollisions = true;
        log.error(
            "Username collision on normalized name '{}': {} users share it, left unchanged. {}",
            entry.getKey(),
            group.size(),
            group.stream()
                .map(u -> "id=" + u.getId() + " username='" + u.getUsername() + "'")
                .toList());
      }
    }

    if (hasCollisions) {
      log.error(
          "Unique index '{}' on users.username is NOT created: pending a manual fix of the colliding usernames above. Restart after fixing.",
          INDEX_NAME);
      return;
    }

    mongoTemplate
        .indexOps(User.class)
        .createIndex(
            new Index().on("username", Sort.Direction.ASC).unique().named(INDEX_NAME));
    log.info("Ensured unique index '{}' on users.username", INDEX_NAME);
  }

  private void lowercaseIfNeeded(User user, String canonical) {
    if (canonical.equals(user.getUsername())) {
      return;
    }
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(user.getId())),
        Update.update("username", canonical),
        User.class);
    log.info("Normalized legacy username '{}' to '{}' (id={})", user.getUsername(), canonical, user.getId());
  }
}
