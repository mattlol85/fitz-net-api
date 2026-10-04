package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import org.fitznet.fitznetapi.config.AdminApiKeyFilter;
import org.fitznet.fitznetapi.dto.requests.UpdatePermissionsRequestDto;
import org.fitznet.fitznetapi.dto.responses.UserPermissionsResponseDto;
import org.fitznet.fitznetapi.dto.responses.UserResponseDto;
import org.fitznet.fitznetapi.model.Permission;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only endpoints for managing per-user permissions (ADMIN user JWT or X-Admin-Key). */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasAuthority('PERM_ADMIN')")
public class PermissionController {

  private static final Logger log = LoggerFactory.getLogger(PermissionController.class);

  @Autowired UserService userService;

  @GetMapping("/permissions")
  public List<String> listPermissions() {
    return Arrays.stream(Permission.values()).map(Enum::name).toList();
  }

  @GetMapping("/users")
  public List<UserResponseDto> listUsers() {
    return userService.findAll().stream().map(UserController::toUserResponse).toList();
  }

  @GetMapping("/users/{username}/permissions")
  public UserPermissionsResponseDto getPermissions(@PathVariable String username) {
    User user = userService.readByUsername(username);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    return toResponse(user);
  }

  @PutMapping("/users/{username}/permissions")
  public UserPermissionsResponseDto setPermissions(
      @PathVariable String username, @RequestBody @Valid UpdatePermissionsRequestDto request) {
    log.info("Setting permissions for {} to {} (by {})", username, request.getPermissions(), caller());
    guardSelfAdminRemoval(username, request.getPermissions().contains(Permission.ADMIN));
    return toResponse(
        requireUser(
            userService.setPermissions(
                username, request.getPermissions().stream().map(Enum::name).toList())));
  }

  @PostMapping("/users/{username}/permissions/{permission}")
  public UserPermissionsResponseDto addPermission(
      @PathVariable String username, @PathVariable Permission permission) {
    log.info("Granting {} to {} (by {})", permission, username, caller());
    return toResponse(requireUser(userService.addPermission(username, permission.name())));
  }

  @DeleteMapping("/users/{username}/permissions/{permission}")
  public UserPermissionsResponseDto removePermission(
      @PathVariable String username, @PathVariable Permission permission) {
    log.info("Revoking {} from {} (by {})", permission, username, caller());
    guardSelfAdminRemoval(username, permission != Permission.ADMIN);
    return toResponse(requireUser(userService.removePermission(username, permission.name())));
  }

  /** JWT admins cannot strip their own ADMIN permission; the API key is exempt (no lockout). */
  private void guardSelfAdminRemoval(String targetUsername, boolean keepsAdmin) {
    String caller = caller();
    if (!keepsAdmin
        && !AdminApiKeyFilter.PRINCIPAL.equals(caller)
        && caller.equals(targetUsername)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "You cannot remove your own ADMIN permission");
    }
  }

  private static String caller() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  private static User requireUser(User user) {
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    return user;
  }

  private static UserPermissionsResponseDto toResponse(User user) {
    return new UserPermissionsResponseDto(user.getUsername(), UserController.permissionsOf(user));
  }
}
