package org.fitznet.fitznetapi.util;

import java.util.Locale;

/** Canonical form for usernames: trimmed and lowercased so lookups are case-insensitive. */
public final class UsernameNormalizer {

  private UsernameNormalizer() {}

  public static String normalize(String s) {
    return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
  }
}
