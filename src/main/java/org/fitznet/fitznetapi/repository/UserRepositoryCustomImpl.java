package org.fitznet.fitznetapi.repository;

import static java.util.Objects.nonNull;

import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class UserRepositoryCustomImpl implements UserRepositoryCustom {

  private final MongoTemplate mongoTemplate;
  private final PasswordEncoder passwordEncoder;

  @Autowired
  public UserRepositoryCustomImpl(MongoTemplate mongoTemplate, PasswordEncoder passwordEncoder) {
    this.mongoTemplate = mongoTemplate;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public User findAndModifyUser(UpdateUserRequestDto updateRequest) {
    log.debug("Executing findAndModify for user: {}", updateRequest.getUsername());

    // Build the query to find the user by username
    Query query = new Query(Criteria.where("username").is(updateRequest.getUsername()));

    // Build the update operations
    Update update = new Update();
    boolean hasUpdates = false;

    if (nonNull(updateRequest.getUpdatedUsername())) {
      log.debug("Adding username update to query");
      update.set("username", updateRequest.getUpdatedUsername());
      hasUpdates = true;
    }

    if (nonNull(updateRequest.getUpdatedEmail())) {
      log.debug("Adding email update to query");
      update.set("email", updateRequest.getUpdatedEmail());
      hasUpdates = true;
    }

    if (nonNull(updateRequest.getUpdatedPassword())) {
      log.debug("Adding password update to query");
      String hashedPassword = passwordEncoder.encode(updateRequest.getUpdatedPassword());
      update.set("password", hashedPassword);
      hasUpdates = true;
    }

    if (!hasUpdates) {
      log.warn("No fields to update for user: {}", updateRequest.getUsername());
      return null;
    }

    // Execute findAndModify - returns the updated document
    // Using FindAndModifyOptions to return the new (updated) document
    FindAndModifyOptions options = new FindAndModifyOptions().returnNew(true);
    User updatedUser = mongoTemplate.findAndModify(query, update, options, User.class);

    if (updatedUser == null) {
      log.warn("User not found for update: {}", updateRequest.getUsername());
    } else {
      log.debug("User updated successfully via findAndModify");
    }

    return updatedUser;
  }
}

