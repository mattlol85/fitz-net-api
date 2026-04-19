package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.DeleteUserRequestDto;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateProfileRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.dto.responses.LoginResponseDto;
import org.fitznet.fitznetapi.dto.responses.UpdateProfileResponseDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.UserService;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class UserController {

  @Autowired UserService userService;
  @Autowired JwtUtil jwtUtil;

  static final Logger log = LoggerFactory.getLogger(UserController.class);
  @Autowired private UserRepository userRepository;

  @PostMapping("/user/create")
  public User createUser(@RequestBody @Valid UserDTO user) {
    log.info("Request at /user/create - username: {}", user.getUsername());
    performRequestValidations(user);
    return userService.saveUser(
        User.builder()
            .username(user.getUsername())
            .email(user.getEmail())
            .password(user.getPassword())
            .build());
  }

  @PostMapping("/user/read")
  public User readUser(@RequestBody @NotBlank String username) {
    log.info("Request for /user/read - {}", username);
    return userService.readByUsername(username);
  }

  @GetMapping("/user/readAll")
  public List<User> readAllUsers() {
    log.info("Request for /user/readAll");
    return userService.findAll();
  }

  @DeleteMapping("/user/delete")
  public void deleteUser(@RequestBody @Valid DeleteUserRequestDto user) {
    log.info("Request for /delete");
    if (!doesUserAlreadyExist(user.getUsername())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found in db");
    }
    userService.deleteUser(user.getUsername());
  }

  @PatchMapping("/user/update")
  public void updateUser(@RequestBody @Valid UpdateUserRequestDto updateUserDto) {
    log.info("Request for /update (PATCH)");
    userService.updateUser(updateUserDto);
  }

  @PutMapping("/user/update")
  public UpdateProfileResponseDto updateProfile(@RequestBody @Valid UpdateProfileRequestDto profileRequest) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String currentUsername = auth.getName();
    log.info("Request for /user/update (PUT) - authenticated user: {}", currentUsername);

    // Map the simple profile request to the internal update DTO
    UpdateUserRequestDto updateDto = new UpdateUserRequestDto();
    updateDto.setUsername(currentUsername);

    boolean hasUpdates = false;

    if (profileRequest.getUsername() != null && !profileRequest.getUsername().equals(currentUsername)) {
      updateDto.setUpdatedUsername(profileRequest.getUsername());
      hasUpdates = true;
    }

    if (profileRequest.getEmail() != null) {
      updateDto.setUpdatedEmail(profileRequest.getEmail());
      hasUpdates = true;
    }

    if (profileRequest.getPassword() != null && !profileRequest.getPassword().isBlank()) {
      updateDto.setUpdatedPassword(profileRequest.getPassword());
      hasUpdates = true;
    }

    if (!hasUpdates) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No fields to update");
    }

    User updatedUser = userService.updateUser(updateDto);

    if (updatedUser == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }

    return new UpdateProfileResponseDto(true, "Profile updated successfully", updatedUser.getUsername(), updatedUser.getEmail());
  }

  @PostMapping("/user/login")
  public LoginResponseDto login(@RequestBody @Valid LoginRequestDto loginRequest) {
    log.info("Request for /user/login - {}", loginRequest.getUsername());

    boolean isValid = userService.verifyPassword(loginRequest.getUsername(), loginRequest.getPassword());

    if (isValid) {
      User user = userService.readByUsername(loginRequest.getUsername());
      String token = jwtUtil.generateToken(user.getUsername());
      return new LoginResponseDto(true, "Login successful", user.getUsername(), user.getEmail(), token);
    } else {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
  }

  private boolean doesUserAlreadyExist(String username) {
    var possibleUser = userRepository.findByUsername(username);
    log.info("Checking to see if user {} exists in db", username);
    return null != possibleUser;
  }

  private boolean doesUserAlreadyExist(UserDTO user) {
    var possibleUser = userRepository.findByUsername(user.getUsername());
    log.info("Checking to see if user {} exists in db", user.getUsername());
    return null != possibleUser;
  }

  private boolean isEmailAlreadyInUse(UserDTO user) {
    var possibleUser = userRepository.findByEmail(user.getEmail());
    log.info("Checking to see if email {} exists in db", user.getEmail());
    return null != possibleUser;
  }

  private void performRequestValidations(UserDTO user) {
    if (doesUserAlreadyExist(user)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
    }

    if (isEmailAlreadyInUse(user)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email in use");
    }

    if (user.getPassword() == null || user.getPassword().length() < 8) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters long");
    }
  }
}
