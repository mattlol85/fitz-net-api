package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.DeleteUserRequestDto;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.dto.requests.ReadSingleAccountRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.dto.responses.LoginResponseDto;
import org.fitznet.fitznetapi.dto.responses.UserResponseDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class UserController {

  @Autowired UserService userService;

  static final Logger log = LoggerFactory.getLogger(UserController.class);
  @Autowired private UserRepository userRepository;

  @PostMapping("/user/create")
  public ResponseEntity<UserResponseDto> createUser(@Valid @RequestBody UserDTO user) {
    log.info("Request at /user - {}", user.toString());
    user.sanitizeUsernameAndEmail();
    performRequestValidations(user);
    User saved = userService.saveUser(
        User.builder()
            .username(user.getUsername())
            .email(user.getEmail())
            .password(user.getPassword())
            .build());
    return ResponseEntity.status(HttpStatus.CREATED).body(UserResponseDto.fromUser(saved));
  }

  @PostMapping("/user/read")
  public ResponseEntity<UserResponseDto> readUser(@RequestBody ReadSingleAccountRequestDto username) {
    log.info("Request for /user/read - {}", username);
    User found = userService.readByUsername(username.getUsername());
    if (found == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    return ResponseEntity.ok(UserResponseDto.fromUser(found));
  }

  @GetMapping("/user/readAll")
  public ResponseEntity<List<UserResponseDto>> readAllUsers() {
    log.info("Request for /user/readAll");
    List<UserResponseDto> users = userService.findAll().stream()
        .map(UserResponseDto::fromUser)
        .collect(Collectors.toList());
    return ResponseEntity.ok(users);
  }

  @DeleteMapping("/user/delete")
  public ResponseEntity<Void> deleteUser(@RequestBody DeleteUserRequestDto user) {
    log.info("Request for /delete");
    if (!doesUserAlreadyExist(user.getUsername())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found in db");
    }
    userService.deleteUser(user.getUsername());
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/user/update")
  public ResponseEntity<UserResponseDto> updateUser(@RequestBody UpdateUserRequestDto updateUserDto) {
    log.info("Request for /update");
    User updated = userService.updateUser(updateUserDto);
    if (updated == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found or nothing to update");
    }
    return ResponseEntity.ok(UserResponseDto.fromUser(updated));
  }

  @PostMapping("/user/login")
  public ResponseEntity<LoginResponseDto> login(@RequestBody LoginRequestDto loginRequest) {
    log.info("Request for /user/login - {}", loginRequest.getUsername());

    boolean isValid = userService.verifyPassword(loginRequest.getUsername(), loginRequest.getPassword());

    if (isValid) {
      User user = userService.readByUsername(loginRequest.getUsername());
      LoginResponseDto response = new LoginResponseDto(true, "Login successful", user.getUsername(), user.getEmail());
      return ResponseEntity.ok(response);
    } else {
      LoginResponseDto response = new LoginResponseDto(false, "Invalid username or password", null, null);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }
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

  private boolean isEmailAlreadyInUse(UserDTO user) {
    var possibleUser = userRepository.findByEmail(user.getEmail());
    log.info("Checking to see if email {} exists in db", user.getEmail());
    return null != possibleUser;
  }

  private boolean doesUserAlreadyExist(UserDTO user) {
    var possibleUser = userRepository.findByUsername(user.getUsername());
    log.info("Checking to see if user {} exists in db", user.getUsername());
    return null != possibleUser;
  }

  private boolean doesUserAlreadyExist(String username) {
    var possibleUser = userRepository.findByUsername(username);
    log.info("Checking to see if user {} exists in db", username);
    return null != possibleUser;
  }
}
