package org.fitznet.fitznetapi.service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserService {

  final UserRepository userRepository;
  final PasswordEncoder passwordEncoder;

  @Autowired
  public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  public User saveUser(User user) {
    log.info("Saving user... - {}", user.getUsername());
    // Hash the password before saving
    user.setPassword(passwordEncoder.encode(user.getPassword()));
    // Assign a board color if not already set
    if (user.getBoardColor() == null || user.getBoardColor().isBlank()) {
      user.setBoardColor(generateBoardColor());
    }
    return userRepository.save(user);
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
    log.info("Deleting user - {}", username);
    userRepository.deleteByUsername(username);
  }

  public User readByUsername(String username) {
    return userRepository.findByUsername(username);
  }

  public User updateUser(UpdateUserRequestDto updateRequest) {
    log.info("Updating user: {}", updateRequest.getUsername());

    User updatedUser = userRepository.findAndModifyUser(updateRequest);

    if (updatedUser == null) {
      log.warn("User not found or no fields to update: {}", updateRequest.getUsername());
      return null;
    }

    log.info("User updated successfully: {}", updateRequest.getUsername());
    return updatedUser;
  }

  public boolean verifyPassword(String username, String rawPassword) {
    log.info("Verifying password for user: {}", username);
    var user = userRepository.findByUsername(username);
    if (null == user) {
      log.warn("User not found: {}", username);
      return false;
    }
    return passwordEncoder.matches(rawPassword, user.getPassword());
  }

  public List<User> findAll() {
    return userRepository.findAll();
  }
}

