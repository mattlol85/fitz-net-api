package org.fitznet.fitznetapi.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.model.PermissionDefinition;
import org.fitznet.fitznetapi.model.Permissions;
import org.fitznet.fitznetapi.repository.PermissionRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

/** Manages the runtime-defined permission catalogue stored in Mongo. */
@Slf4j
@Service
public class PermissionService implements ApplicationRunner {

  private final PermissionRepository permissionRepository;
  private final UserRepository userRepository;

  @Autowired
  public PermissionService(
      PermissionRepository permissionRepository, UserRepository userRepository) {
    this.permissionRepository = permissionRepository;
    this.userRepository = userRepository;
  }

  /** ADMIN is the one built-in permission; make sure it is always defined. */
  @Override
  public void run(ApplicationArguments args) {
    ensureAdminDefined();
  }

  void ensureAdminDefined() {
    if (!permissionRepository.existsById(Permissions.ADMIN)) {
      permissionRepository.save(
          PermissionDefinition.builder()
              .name(Permissions.ADMIN)
              .description("Manage users and permissions")
              .build());
    }
  }

  public List<PermissionDefinition> list() {
    ensureAdminDefined();
    return permissionRepository.findAll();
  }

  public Set<String> names() {
    return list().stream().map(PermissionDefinition::getName).collect(Collectors.toSet());
  }

  /** Names in {@code requested} that are not defined. */
  public Set<String> unknown(Collection<String> requested) {
    Set<String> defined = names();
    return requested.stream().filter(p -> !defined.contains(p)).collect(Collectors.toSet());
  }

  public boolean exists(String name) {
    return Permissions.ADMIN.equals(name) || permissionRepository.existsById(name);
  }

  /** Returns null if the permission already exists. */
  public PermissionDefinition create(String name, String description) {
    if (exists(name)) {
      return null;
    }
    return permissionRepository.save(
        PermissionDefinition.builder().name(name).description(description).build());
  }

  /** Deletes the definition and revokes it from every user. Returns false if it was not defined. */
  public boolean delete(String name) {
    if (!permissionRepository.existsById(name)) {
      return false;
    }
    permissionRepository.deleteById(name);
    userRepository.removePermissionFromAll(name);
    log.info("Deleted permission {} and revoked it from all users", name);
    return true;
  }
}
