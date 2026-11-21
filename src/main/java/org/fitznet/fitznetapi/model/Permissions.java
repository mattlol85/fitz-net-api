package org.fitznet.fitznetapi.model;

/**
 * The permissions the code itself depends on. Every other permission is defined at runtime in
 * the {@code permissions} Mongo collection (see {@link PermissionDefinition}).
 */
public final class Permissions {

  public static final String ADMIN = "ADMIN";
  public static final String AI_USER = "AI_USER";
  /** Lori-Net (lori.fitznet.org) and its recipe books. */
  public static final String LORINET_ACCESS = "LORINET_ACCESS";
  public static final String AUTHORITY_PREFIX = "PERM_";

  private Permissions() {}

  public static String authority(String permission) {
    return AUTHORITY_PREFIX + permission;
  }
}
