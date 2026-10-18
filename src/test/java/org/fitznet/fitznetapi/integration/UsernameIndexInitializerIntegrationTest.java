package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.config.UsernameIndexInitializer;
import org.fitznet.fitznetapi.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
@Import(EmbeddedMongoTestConfiguration.class)
class UsernameIndexInitializerIntegrationTest {

  private static final String INDEX_NAME = "username_unique";

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private UsernameIndexInitializer initializer;

  @BeforeEach
  void setUp() {
    dropUsernameIndexIfPresent();
    mongoTemplate.remove(new Query(), User.class);
  }

  @AfterEach
  void tearDown() {
    mongoTemplate.remove(new Query(), User.class);
  }

  @Test
  void shouldLowercaseLegacyMixedCaseUsernames() {
    String mixedId = seed("ZMat");
    String aliceId = seed("Alice");
    String bobId = seed("bob");

    initializer.run(new DefaultApplicationArguments());

    assertEquals("zmat", storedUsername(mixedId));
    assertEquals("alice", storedUsername(aliceId));
    assertEquals("bob", storedUsername(bobId));
  }

  @Test
  void shouldCreateUniqueIndexWhenNoCollisions() {
    seed("ZMat");

    initializer.run(new DefaultApplicationArguments());

    IndexInfo index = findUsernameIndex();
    assertNotNull(index, "username_unique index should exist");
    assertTrue(index.isUnique());

    assertThrows(DuplicateKeyException.class, () -> seed("zmat"));
  }

  @Test
  void shouldSkipIndexAndKeepDocumentsWhenCollisionsExist() {
    String upperId = seed("Zmat");
    String lowerId = seed("zmat");
    String aliceId = seed("Alice");

    initializer.run(new DefaultApplicationArguments());

    assertNull(findUsernameIndex(), "index must not be created while collisions exist");
    // Colliding documents are left exactly as they were: nothing merged, deleted or renamed.
    assertEquals("Zmat", storedUsername(upperId));
    assertEquals("zmat", storedUsername(lowerId));
    assertEquals(3, mongoTemplate.count(new Query(), User.class));
    // Non-colliding legacy names are still migrated.
    assertEquals("alice", storedUsername(aliceId));
  }

  @Test
  void shouldBeIdempotentOnSecondRun() {
    String mixedId = seed("ZMat");
    String aliceId = seed("Alice");

    initializer.run(new DefaultApplicationArguments());
    initializer.run(new DefaultApplicationArguments());

    assertEquals("zmat", storedUsername(mixedId));
    assertEquals("alice", storedUsername(aliceId));
    assertEquals(2, mongoTemplate.count(new Query(), User.class));
    IndexInfo index = findUsernameIndex();
    assertNotNull(index, "username_unique index should exist after repeated runs");
    assertTrue(index.isUnique());
  }

  private String seed(String username) {
    User saved =
        mongoTemplate.insert(
            User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("x")
                .build());
    return saved.getId();
  }

  private String storedUsername(String id) {
    User user = mongoTemplate.findById(id, User.class);
    assertNotNull(user);
    return user.getUsername();
  }

  private IndexInfo findUsernameIndex() {
    return mongoTemplate.indexOps(User.class).getIndexInfo().stream()
        .filter(info -> INDEX_NAME.equals(info.getName()))
        .findFirst()
        .orElse(null);
  }

  private void dropUsernameIndexIfPresent() {
    if (findUsernameIndex() != null) {
      mongoTemplate.indexOps(User.class).dropIndex(INDEX_NAME);
    }
  }
}
