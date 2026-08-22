package org.fitznet.fitznetapi.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.fitznet.fitznetapi.model.NodeEnrollmentToken;
import org.fitznet.fitznetapi.repository.NodeEnrollmentTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NodeEnrollmentService {

  private static final Logger logger = LoggerFactory.getLogger(NodeEnrollmentService.class);
  private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

  private final NodeEnrollmentTokenRepository tokenRepository;
  private final SecureRandom secureRandom = new SecureRandom();

  public NodeEnrollmentService(NodeEnrollmentTokenRepository tokenRepository) {
    this.tokenRepository = tokenRepository;
  }

  public NodeEnrollmentToken generateToken(String createdByUsername, String label) {
    byte[] randomBytes = new byte[32];
    secureRandom.nextBytes(randomBytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

    NodeEnrollmentToken enrollmentToken =
        NodeEnrollmentToken.builder()
            .id(token)
            .createdByUsername(createdByUsername)
            .label(label)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(TOKEN_TTL))
            .used(false)
            .build();

    logger.info("Generated node enrollment token for user: {}", createdByUsername);
    return tokenRepository.save(enrollmentToken);
  }

  /** Validates and consumes a token, returning it. Throws if the token is unknown, expired, or already used. */
  public NodeEnrollmentToken consumeToken(String token) {
    NodeEnrollmentToken enrollmentToken =
        tokenRepository
            .findById(token)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid enrollment token"));

    if (enrollmentToken.isUsed()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Enrollment token already used");
    }

    if (Instant.now().isAfter(enrollmentToken.getExpiresAt())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Enrollment token expired");
    }

    enrollmentToken.setUsed(true);
    tokenRepository.save(enrollmentToken);
    return enrollmentToken;
  }
}
