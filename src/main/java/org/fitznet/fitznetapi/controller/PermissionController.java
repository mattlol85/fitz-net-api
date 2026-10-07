package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.fitznet.fitznetapi.config.AdminApiKeyFilter;
import org.fitznet.fitznetapi.dto.requests.CreatePermissionRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdatePermissionsRequestDto;
import org.fitznet.fitznetapi.dto.responses.UserPermissionsResponseDto;
import org.fitznet.fitznetapi.dto.responses.UserResponseDto;
import org.fitznet.fitznetapi.model.PermissionDefinition;
import org.fitznet.fitznetapi.model.Permissions;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.service.PermissionService;
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
  @Autowired PermissionService permissionService;

  @GetMapping("/permissions")
  public List<PermissionDefinition> listPermissions() {
    return permissionService.list();
  }

  @PostMapping("/permissions")
  public PermissionDefinition createPermission(
      @RequestBody @Valid CreatePermissionRequestDto request) {
    log.info("Creating permission {} (by {})", request.getName(), caller());
    PermissionDefinition created =
        permissionService.create(request.getName(), request.getDescription());
    if (created == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Permission already exists");
    }
    return created;
  }

  @DeleteMapping("/permissions/{permission}")
  public void deletePermission(@PathVariable String permission) {
    log.info("Deleting permission {} (by {})", permission, caller());
    if (Permissions.ADMIN.equals(permission)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN cannot be deleted");
    }
    if (!permissionService.delete(permission)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Permission not found");
    }
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
    requireDefined(request.getPermissions());
    guardSelfAdminRemoval(username, request.getPermissions().contains(Permissions.ADMIN));
    User updated = requireUser(userService.setPermissions(username, request.getPermissions()));
    return toResponse(revokeIfUndefinedMeanwhile(username, request.getPermissions(), updated));
  }

  @PostMapping("/users/{username}/permissions/{permission}")
  public UserPermissionsResponseDto addPermission(
      @PathVariable String username, @PathVariable String permission) {
    requireDefined(Set.of(permission));
    log.info("Granting {} to {} (by {})", permission, username, caller());
    User updated = requireUser(userService.addPermission(username, permission));
    return toResponse(revokeIfUndefinedMeanwhile(username, Set.of(permission), updated));
  }

  @DeleteMapping("/users/{username}/permissions/{permission}")
  public UserPermissionsResponseDto removePermission(
      @PathVariable String username, @PathVariable String permission) {
    log.info("Revoking {} from {} (by {})", permission, username, caller());
    guardSelfAdminRemoval(username, !Permissions.ADMIN.equals(permission));
    return toResponse(requireUser(userService.removePermission(username, permission)));
  }

  /** JWT admins cannot strip their own ADMIN permission; the API key is exempt (no lockout). */
  private void guardSelfAdminRemoval(String targetUsername, boolean keepsAdmin) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!keepsAdmin
        && !(auth instanceof AdminApiKeyFilter.ApiKeyAuthentication)
        && auth.getName().equals(targetUsername)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "You cannot remove your own ADMIN permission");
    }
  }

  /**
   * A permission can be deleted between {@link #requireDefined} and the write. Re-check afterwards
   * and undo any grant of a permission that no longer exists, so no orphan grants survive (either
   * the delete's revoke sees our grant, or we see the delete here).
   */
  private User revokeIfUndefinedMeanwhile(String username, Set<String> granted, User updated) {
    Set<String> gone = permissionService.unknown(granted);
    User result = updated;
    for (String permission : gone) {
      result = userService.removePermission(username, permission);
    }
    if (!gone.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Permission was deleted concurrently: " + String.join(", ", gone));
    }
    return result;
  }

  private void requireDefined(Set<String> requested) {
    Set<String> unknown = permissionService.unknown(requested);
    if (!unknown.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unknown permission(s): " + String.join(", ", unknown));
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
