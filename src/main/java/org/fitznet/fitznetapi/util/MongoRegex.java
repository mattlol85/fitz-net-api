package org.fitznet.fitznetapi.util;

import java.util.regex.Pattern;

/** Helpers for user-typed text used inside Mongo {@code $regex} queries. */
public final class MongoRegex {

  private static final Pattern REGEX_META = Pattern.compile("[\\\\.^$|?*+()\\[\\]{}]");

  private MongoRegex() {}

  /** Escapes every regex metacharacter so the term matches literally. */
  public static String literal(String term) {
    return REGEX_META.matcher(term).replaceAll("\\\\$0");
  }
}
