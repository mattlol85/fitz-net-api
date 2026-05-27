package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.*;

import org.fitznet.fitznetapi.dto.liveboard.BoardMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class LiveBoardStateTest {

  private LiveBoardState state;

  @BeforeEach
  void setUp() {
    state = new LiveBoardState();
  }

  @Test
  void addSessionShouldIncrementUserCount() {
    state.addSession("session-1", "alice");
    assertEquals(1, state.getUserCount());
  }

  @Test
  void removeSessionShouldDecrementUserCountAndReturnUsername() {
    state.addSession("session-1", "alice");
    String removed = state.removeSession("session-1");
    assertEquals("alice", removed);
    assertEquals(0, state.getUserCount());
  }

  @Test
  void removeUnknownSessionShouldReturnNullAndNotDecrementCount() {
    state.addSession("session-1", "alice");
    String removed = state.removeSession("unknown-session");
    assertNull(removed);
    assertEquals(1, state.getUserCount());
  }

  @Test
  void addMessageShouldStoreMessage() {
    BoardMessageDto msg = BoardMessageDto.create("alice", 0.5, 0.5, "hello");
    state.addMessage(msg);
    List<BoardMessageDto> messages = state.getMessages();
    assertEquals(1, messages.size());
    assertEquals("alice", messages.get(0).getUsername());
    assertEquals("hello", messages.get(0).getContent());
  }

  @Test
  void clearBoardIfEmptyShouldClearWhenCountIsZero() {
    state.addMessage(BoardMessageDto.create("alice", 0.1, 0.2, "test"));
    // No sessions added, count stays 0
    boolean cleared = state.clearBoardIfEmpty();
    assertTrue(cleared);
    assertTrue(state.getMessages().isEmpty());
  }

  @Test
  void clearBoardIfEmptyShouldNotClearWhenUsersArePresent() {
    state.addSession("session-1", "alice");
    state.addMessage(BoardMessageDto.create("alice", 0.1, 0.2, "test"));
    boolean cleared = state.clearBoardIfEmpty();
    assertFalse(cleared);
    assertEquals(1, state.getMessages().size());
  }

  @Test
  void clearBoardIfEmptyShouldClearAfterLastUserLeaves() {
    state.addSession("s1", "alice");
    state.addSession("s2", "bob");
    state.addMessage(BoardMessageDto.create("alice", 0.1, 0.2, "hey"));

    state.removeSession("s1");
    assertFalse(state.clearBoardIfEmpty()); // bob still there

    state.removeSession("s2");
    assertTrue(state.clearBoardIfEmpty()); // now empty
    assertTrue(state.getMessages().isEmpty());
  }

  @Test
  void getActiveUsernamesShouldReflectCurrentSessions() {
    state.addSession("s1", "alice");
    state.addSession("s2", "bob");
    assertTrue(state.getActiveUsernames().contains("alice"));
    assertTrue(state.getActiveUsernames().contains("bob"));

    state.removeSession("s1");
    assertFalse(state.getActiveUsernames().contains("alice"));
    assertTrue(state.getActiveUsernames().contains("bob"));
  }

  @Test
  void getMessagesShouldReturnSnapshot() {
    BoardMessageDto m1 = BoardMessageDto.create("alice", 0.1, 0.2, "a");
    state.addMessage(m1);
    List<BoardMessageDto> snapshot = state.getMessages();
    // Adding another message should not affect the already-retrieved snapshot
    state.addMessage(BoardMessageDto.create("bob", 0.3, 0.4, "b"));
    assertEquals(1, snapshot.size());
    assertEquals(2, state.getMessages().size());
  }
}

