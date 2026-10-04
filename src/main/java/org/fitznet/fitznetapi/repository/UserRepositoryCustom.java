package org.fitznet.fitznetapi.repository;

import java.util.Collection;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;

public interface UserRepositoryCustom {
  User findAndModifyUser(UpdateUserRequestDto updateRequest);

  /** Replace the permission set; returns the updated user or null if not found. */
  User setPermissions(String username, Collection<String> permissions);

  User addPermission(String username, String permission);

  User removePermission(String username, String permission);
}

