package org.fitznet.fitznetapi.model;

/** Fine-grained permissions that can be granted to a user. Stored on the user as strings. */
public enum Permission {
  ADMIN,
  RADARR,
  SONARR;

  public static final String AUTHORITY_PREFIX = "PERM_";

  public String authority() {
    return AUTHORITY_PREFIX + name();
  }
}
