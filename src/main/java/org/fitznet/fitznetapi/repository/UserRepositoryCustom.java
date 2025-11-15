package org.fitznet.fitznetapi.repository;

import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;

public interface UserRepositoryCustom {
  User findAndModifyUser(UpdateUserRequestDto updateRequest);
}

