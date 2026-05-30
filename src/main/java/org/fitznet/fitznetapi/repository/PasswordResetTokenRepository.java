package org.fitznet.fitznetapi.repository;

import java.util.Optional;
import org.fitznet.fitznetapi.model.PasswordResetToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordResetTokenRepository extends MongoRepository<PasswordResetToken, String> {

  Optional<PasswordResetToken> findByTokenAndUsedFalse(String token);

  void deleteByEmail(String email);
}
