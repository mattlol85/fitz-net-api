package org.fitznet.fitznetapi.service;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.model.PasswordResetToken;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.PasswordResetTokenRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PasswordResetService {

  private final UserRepository userRepository;
  private final PasswordResetTokenRepository tokenRepository;
  private final EmailService emailService;
  private final PasswordEncoder passwordEncoder;
  private final MongoTemplate mongoTemplate;

  public PasswordResetService(
      UserRepository userRepository,
      PasswordResetTokenRepository tokenRepository,
      EmailService emailService,
      PasswordEncoder passwordEncoder,
      MongoTemplate mongoTemplate) {
    this.userRepository = userRepository;
    this.tokenRepository = tokenRepository;
    this.emailService = emailService;
    this.passwordEncoder = passwordEncoder;
    this.mongoTemplate = mongoTemplate;
  }

  public void initiateReset(String email) {
    User user = userRepository.findByEmail(email);
    if (user == null) {
      // Return silently — never reveal whether an email is registered
      log.debug("Password reset requested for unknown email: {}", email);
      return;
    }

    tokenRepository.deleteByEmail(email);

    PasswordResetToken token = PasswordResetToken.builder()
        .email(email)
        .token(UUID.randomUUID().toString())
        .expiresAt(LocalDateTime.now().plusMinutes(15))
        .used(false)
        .build();

    tokenRepository.save(token);
    emailService.sendPasswordReset(email, token.getToken());
  }

  public boolean resetPassword(String rawToken, String newPassword) {
    return tokenRepository.findByTokenAndUsedFalse(rawToken)
        .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()))
        .map(t -> {
          Query query = new Query(Criteria.where("email").is(t.getEmail()));
          Update update = new Update().set("password", passwordEncoder.encode(newPassword));
          mongoTemplate.updateFirst(query, update, User.class);

          t.setUsed(true);
          tokenRepository.save(t);
          log.info("Password reset successfully for {}", t.getEmail());
          return true;
        })
        .orElse(false);
  }
}
