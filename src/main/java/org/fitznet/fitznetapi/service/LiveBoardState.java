package org.fitznet.fitznetapi.service;

import org.fitznet.fitznetapi.dto.liveboard.BoardMessageDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LiveBoardState {

  /** sessionId -> username for all currently connected users */
  private final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<>();

  /** All messages currently visible on the board */
  private final CopyOnWriteArrayList<BoardMessageDto> messages = new CopyOnWriteArrayList<>();

  /** Atomic count of active users */
  private final AtomicInteger userCount = new AtomicInteger(0);

  public void addSession(String sessionId, String username) {
    sessions.put(sessionId, username);
    userCount.incrementAndGet();
  }

  /**
   * Removes a session.
   *
   * @return the username that was removed, or null if session was unknown
   */
  public String removeSession(String sessionId) {
    String username = sessions.remove(sessionId);
    if (username != null) {
      userCount.decrementAndGet();
    }
    return username;
  }

  public void addMessage(BoardMessageDto message) {
    messages.add(message);
  }

  public List<BoardMessageDto> getMessages() {
    return new ArrayList<>(messages);
  }

  /**
   * Atomically checks whether the user count has reached zero and, if so, clears all messages.
   *
   * @return true if the board was cleared
   */
  public synchronized boolean clearBoardIfEmpty() {
    if (userCount.get() <= 0) {
      messages.clear();
      sessions.clear();
      userCount.set(0);
      return true;
    }
    return false;
  }

  public int getUserCount() {
    return userCount.get();
  }

  public Collection<String> getActiveUsernames() {
    return sessions.values();
  }
}

