package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import org.fitznet.fitznetapi.config.AdminApiKeyFilter;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateProfileRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.dto.responses.DeleteUserResponseDto;
import org.fitznet.fitznetapi.dto.responses.LoginResponseDto;
import org.fitznet.fitznetapi.dto.responses.UpdateProfileResponseDto;
import org.fitznet.fitznetapi.dto.responses.UserResponseDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.UserService;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.fitznet.fitznetapi.util.UsernameNormalizer;
import org.slf4j.Logger;
import org.springframework.security.access.prepost.PreAuthorize;
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
  public UserResponseDto createUser(@RequestBody @Valid UserDTO user) {
    log.info("Request at /user/create - username: {}", user.getUsername());
    performRequestValidations(user);
    User saved = userService.saveUser(
        User.builder()
            .username(user.getUsername())
            .email(user.getEmail())
            .password(user.getPassword())
            .build());
    return toUserResponse(saved);
  }

  @PreAuthorize("hasAuthority('PERM_ADMIN')")
  @PostMapping("/user/read")
  public UserResponseDto readUser(@RequestBody @NotBlank String username) {
    log.info("Request for /user/read - {}", username);
    User user = userService.readByUsername(username);
    return user != null ? toUserResponse(user) : null;
  }

  @GetMapping("/user/me")
  public UserResponseDto me() {
    String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
    User user = userService.readByUsername(currentUsername);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    return toUserResponse(user);
  }

  @PreAuthorize("hasAuthority('PERM_ADMIN')")
  @GetMapping("/user/readAll")
  public List<UserResponseDto> readAllUsers() {
    log.info("Request for /user/readAll");
    return userService.findAll().stream().map(UserController::toUserResponse).toList();
  }

  @DeleteMapping("/user/delete")
  public DeleteUserResponseDto deleteUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String currentUsername = auth.getName();
    log.info("Request for /user/delete - authenticated user: {}", currentUsername);
    if (!doesUserAlreadyExist(currentUsername)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found in db");
    }
    userService.deleteUser(currentUsername);
    return new DeleteUserResponseDto(true, "User deleted successfully", currentUsername);
  }

  @PatchMapping("/user/update")
  public UpdateProfileResponseDto updateUser(@RequestBody @Valid UpdateUserRequestDto updateUserDto) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String currentUsername = auth.getName();
    log.info("Request for /user/update (PATCH) - authenticated user: {}", currentUsername);

    // The target account is always the authenticated principal; any body-supplied
    // username is ignored to prevent cross-account modification.
    updateUserDto.setUsername(currentUsername);
    updateUserDto.setUpdatedUsername(validateRename(currentUsername, updateUserDto.getUpdatedUsername()));
    User updatedUser = userService.updateUser(updateUserDto);

    if (updatedUser == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }

    return new UpdateProfileResponseDto(true, "Profile updated successfully", updatedUser.getUsername(), updatedUser.getEmail(), updatedUser.getBoardColor(), permissionsOf(updatedUser));
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

    String renamedTo = validateRename(currentUsername, profileRequest.getUsername());
    if (renamedTo != null) {
      updateDto.setUpdatedUsername(renamedTo);
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

    return new UpdateProfileResponseDto(true, "Profile updated successfully", updatedUser.getUsername(), updatedUser.getEmail(), updatedUser.getBoardColor(), permissionsOf(updatedUser));
  }

  @PostMapping("/user/login")
  public LoginResponseDto login(@RequestBody @Valid LoginRequestDto loginRequest) {
    log.info("Request for /user/login - {}", loginRequest.getUsername());

    boolean isValid = userService.verifyPassword(loginRequest.getUsername(), loginRequest.getPassword());

    if (isValid) {
      User user = userService.readByUsername(loginRequest.getUsername());
      String token = jwtUtil.generateToken(user.getUsername(), permissionsOf(user));
      return new LoginResponseDto(true, "Login successful", user.getUsername(), user.getEmail(), token, user.getBoardColor(), permissionsOf(user));
    } else {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
  }

  private boolean doesUserAlreadyExist(String username) {
    String normalized = UsernameNormalizer.normalize(username);
    var possibleUser = userRepository.findByUsername(normalized);
    log.info("Checking to see if user {} exists in db", normalized);
    return null != possibleUser;
  }

  private boolean doesUserAlreadyExist(UserDTO user) {
    String normalized = UsernameNormalizer.normalize(user.getUsername());
    var possibleUser = userRepository.findByUsername(normalized);
    log.info("Checking to see if user {} exists in db", normalized);
    return null != possibleUser;
  }

  /**
   * Validates a requested rename of the authenticated user. Returns the normalized new username
   * when it is a real rename, or null when nothing should change (no value requested, or the
   * request only restates the current name in a different case).
   */
  private String validateRename(String current, String requested) {
    if (requested == null) {
      return null;
    }
    String normalized = UsernameNormalizer.normalize(requested);
    if (normalized.equals(UsernameNormalizer.normalize(current))) {
      return null;
    }
    if (AdminApiKeyFilter.PRINCIPAL.equals(normalized)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is reserved");
    }
    if (null != userRepository.findByUsername(normalized)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
    }
    return normalized;
  }

  private boolean isEmailAlreadyInUse(UserDTO user) {
    var possibleUser = userRepository.findByEmail(user.getEmail());
    log.info("Checking to see if email {} exists in db", user.getEmail());
    return null != possibleUser;
  }

  static Set<String> permissionsOf(User user) {
    return user.getPermissions() == null ? new HashSet<>() : user.getPermissions();
  }

  static UserResponseDto toUserResponse(User user) {
    return new UserResponseDto(
        user.getId(), user.getUsername(), user.getEmail(), user.getBoardColor(), permissionsOf(user));
  }

  private void performRequestValidations(UserDTO user) {
    if (AdminApiKeyFilter.PRINCIPAL.equalsIgnoreCase(user.getUsername())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is reserved");
    }

    if (doesUserAlreadyExist(user)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
    }

    if (isEmailAlreadyInUse(user)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email in use");
    }

    if (user.getPassword() == null || user.getPassword().length() < 8) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters long");
    }
  }
}
