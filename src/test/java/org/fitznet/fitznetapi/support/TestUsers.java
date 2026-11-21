package org.fitznet.fitznetapi.support;

import java.util.HashSet;
import java.util.Set;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.util.JwtUtil;

/** Feature-neutral test fixture: stores a user with the given permissions and returns a JWT. */
public final class TestUsers {

  private TestUsers() {}

  public static String saveUser(
      UserRepository users, JwtUtil jwt, String username, String... permissions) {
    users.save(
        User.builder()
            .username(username)
            .email(username + "@example.com")
            .password("x")
            .permissions(new HashSet<>(Set.of(permissions)))
            .build());
    return jwt.generateToken(username);
  }
}
