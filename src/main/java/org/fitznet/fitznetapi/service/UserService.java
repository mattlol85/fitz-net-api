package org.fitznet.fitznetapi.service;

import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.metrics.FitzNetMetrics;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final FitzNetMetrics fitzNetMetrics;

  @Autowired
  public UserService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      FitzNetMetrics fitzNetMetrics) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.fitzNetMetrics = fitzNetMetrics;
  }

  public User saveUser(User user) {
    Timer.Sample sample = fitzNetMetrics.startSample();
    try {
      log.info("Saving user... - {}", user.getUsername());
      user.setPassword(passwordEncoder.encode(user.getPassword()));
      if (user.getBoardColor() == null || user.getBoardColor().isBlank()) {
        user.setBoardColor(generateBoardColor());
      }
      User savedUser = userRepository.save(user);
      fitzNetMetrics.recordUserOperation("create", "success", sample);
      return savedUser;
    } catch (RuntimeException ex) {
      fitzNetMetrics.recordUserOperation("create", "error", sample);
      throw ex;
    }
  }

  /**
   * Generates a vibrant HSL color that is visible on both light and dark backgrounds.
   * Saturation 72%, lightness 50% ensures rich color without being too pale or too dark.
   */
  private String generateBoardColor() {
    int hue = ThreadLocalRandom.current().nextInt(360);
    return String.format("hsl(%d,72%%,50%%)", hue);
  }

  public void deleteUser(String username) {
    Timer.Sample sample = fitzNetMetrics.startSample();
    try {
      log.info("Deleting user - {}", username);
      userRepository.deleteByUsername(username);
      fitzNetMetrics.recordUserOperation("delete", "success", sample);
    } catch (RuntimeException ex) {
      fitzNetMetrics.recordUserOperation("delete", "error", sample);
      throw ex;
    }
  }

  public User readByUsername(String username) {
    return userRepository.findByUsername(username);
  }

  public User updateUser(UpdateUserRequestDto updateRequest) {
    Timer.Sample sample = fitzNetMetrics.startSample();
    try {
      log.info("Updating user: {}", updateRequest.getUsername());
      User updatedUser = userRepository.findAndModifyUser(updateRequest);

      if (updatedUser == null) {
        log.warn("User not found or no fields to update: {}", updateRequest.getUsername());
        fitzNetMetrics.recordUserOperation("update", "no_update", sample);
        return null;
      }

      fitzNetMetrics.recordUserOperation("update", "success", sample);
      log.info("User updated successfully: {}", updateRequest.getUsername());
      return updatedUser;
    } catch (RuntimeException ex) {
      fitzNetMetrics.recordUserOperation("update", "error", sample);
      throw ex;
    }
  }

  public boolean verifyPassword(String username, String rawPassword) {
    Timer.Sample sample = fitzNetMetrics.startSample();
    try {
      log.info("Verifying password for user: {}", username);
      User user = userRepository.findByUsername(username);
      if (user == null) {
        log.warn("User not found: {}", username);
        fitzNetMetrics.recordUserOperation("login", "user_not_found", sample);
        return false;
      }

      boolean passwordMatches = passwordEncoder.matches(rawPassword, user.getPassword());
      fitzNetMetrics.recordUserOperation(
          "login", passwordMatches ? "success" : "invalid_credentials", sample);
      return passwordMatches;
    } catch (RuntimeException ex) {
      fitzNetMetrics.recordUserOperation("login", "error", sample);
      throw ex;
    }
  }

  public List<User> findAll() {
    return userRepository.findAll();
  }
}
