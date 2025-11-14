package org.fitznet.fitznetapi.service;

import static java.util.Objects.nonNull;

import java.util.List;
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
    return userRepository.save(user);
  }

  public void deleteUser(String username) {
    log.info("Deleting user - {}", username);
    userRepository.deleteByUsername(username);
  }

  public User readByUsername(String username) {
    return userRepository.findByUsername(username);
  }

  public void updateUser(UpdateUserRequestDto updateRequest) {
    var user = userRepository.findByUsername(updateRequest.getUsername());
    if (null == user) {
      log.warn("User not found for update: {}", updateRequest.getUsername());
      return;
    }

    boolean updated = false;

    if (nonNull(updateRequest.getUpdatedUsername())) {
      log.info("Updating username for user: {}", updateRequest.getUsername());
      user.setUsername(updateRequest.getUpdatedUsername());
      updated = true;
    }

    if (nonNull(updateRequest.getUpdatedEmail())) {
      log.info("Updating email for user: {}", updateRequest.getUsername());
      user.setEmail(updateRequest.getUpdatedEmail());
      updated = true;
    }

    if (nonNull(updateRequest.getUpdatedPassword())) {
      log.info("Updating password for user: {}", updateRequest.getUsername());
      user.setPassword(passwordEncoder.encode(updateRequest.getUpdatedPassword()));
      updated = true;
    }

    if (updated) {
      userRepository.save(user);
      log.info("User updated successfully");
    }
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
