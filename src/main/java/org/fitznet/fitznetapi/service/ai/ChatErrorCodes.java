package org.fitznet.fitznetapi.service.ai;

/** The {@code code} values of a v2 turn's {@code error} event (contract section 7.2). */
public final class ChatErrorCodes {

  public static final String NODE_OFFLINE = "NODE_OFFLINE";
  public static final String MODEL_NOT_ON_NODE = "MODEL_NOT_ON_NODE";
  public static final String NO_NODE_AVAILABLE = "NO_NODE_AVAILABLE";
  public static final String NODE_UNREACHABLE = "NODE_UNREACHABLE";
  public static final String NODE_ERROR = "NODE_ERROR";
  public static final String TIMEOUT = "TIMEOUT";
  public static final String MODEL_NOT_VISION = "MODEL_NOT_VISION";
  public static final String CONTEXT_TOO_SMALL = "CONTEXT_TOO_SMALL";
  public static final String INTERNAL = "INTERNAL";

  private ChatErrorCodes() {}

  /** The first four codes carry {@code alternatives} (possibly empty). */
  public static boolean carriesAlternatives(String code) {
    return NODE_OFFLINE.equals(code)
        || MODEL_NOT_ON_NODE.equals(code)
        || NO_NODE_AVAILABLE.equals(code)
        || NODE_UNREACHABLE.equals(code);
  }
}
