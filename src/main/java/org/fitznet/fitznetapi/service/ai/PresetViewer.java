package org.fitznet.fitznetapi.service.ai;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is looking at presets: the canonical username and the permission names they hold (the
 * {@code PERM_} authorities of the security context without their prefix).
 */
public record PresetViewer(String username, Set<String> permissions) {

  private static final String PREFIX = "PERM_";

  /** The authenticated caller of the current request. */
  public static PresetViewer current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return new PresetViewer(auth.getName(), permissionsOf(auth));
  }

  /**
   * The given user, with permissions taken from the security context when it belongs to that user
   * (a turn runs on the request thread) and none otherwise.
   */
  public static PresetViewer of(String username) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && username.equals(auth.getName())) {
      return new PresetViewer(username, permissionsOf(auth));
    }
    return new PresetViewer(username, Set.of());
  }

  public boolean admin() {
    return permissions.contains("ADMIN");
  }

  private static Set<String> permissionsOf(Authentication auth) {
    return auth.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .filter(a -> a.startsWith(PREFIX))
        .map(a -> a.substring(PREFIX.length()))
        .collect(Collectors.toUnmodifiableSet());
  }
}
