package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.DeleteUserRequestDto;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.dto.responses.LoginResponseDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
  public User createUser(@RequestBody @Valid UserDTO user) {
    log.info("Request at /user/create - username: {}", user.getUsername());
    return userService.saveUser(
        User.builder()
            .username(user.getUsername())
            .email(user.getEmail())
            .password(user.getPassword())
            .build());
  }

  @PostMapping("/user/read")
  public User readUser(@NotBlank String username) {
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
    log.info("Request for /update");
    userService.updateUser(updateUserDto);
  }

  @PostMapping("/user/login")
  public LoginResponseDto login(@RequestBody @Valid LoginRequestDto loginRequest) {
    log.info("Request for /user/login - {}", loginRequest.getUsername());

    boolean isValid = userService.verifyPassword(loginRequest.getUsername(), loginRequest.getPassword());

    if (isValid) {
      User user = userService.readByUsername(loginRequest.getUsername());
      return new LoginResponseDto(true, "Login successful", user.getUsername(), user.getEmail());
    } else {
      return new LoginResponseDto(false, "Invalid username or password", null, null);
    }
  }

  private boolean doesUserAlreadyExist(String username) {
    var possibleUser = userRepository.findByUsername(username);
    log.info("Checking to see if user {} exists in db", username);
    return null != possibleUser;
  }
}
