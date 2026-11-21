package org.fitznet.fitznetapi.util;

/** Shared {@code Cache-Control} values. */
public final class HttpCaching {

  /**
   * For content that never changes once stored (uploaded images): the browser may keep it in its
   * private cache for a year.
   */
  public static final String PRIVATE_IMMUTABLE = "private, max-age=31536000, immutable";

  private HttpCaching() {}
}
