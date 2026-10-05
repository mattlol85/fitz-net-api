package org.fitznet.fitznetapi.model;

/**
 * The only permission the code itself depends on. Every other permission is defined at runtime in
 * the {@code permissions} Mongo collection (see {@link PermissionDefinition}).
 */
public final class Permissions {

  public static final String ADMIN = "ADMIN";
  public static final String AUTHORITY_PREFIX = "PERM_";

  private Permissions() {}

  public static String authority(String permission) {
    return AUTHORITY_PREFIX + permission;
  }
}
