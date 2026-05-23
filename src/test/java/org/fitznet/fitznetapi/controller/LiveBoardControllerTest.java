package org.fitznet.fitznetapi.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

import org.fitznet.fitznetapi.dto.liveboard.BoardMessageDto;
import org.fitznet.fitznetapi.dto.liveboard.BoardStateDto;
import org.fitznet.fitznetapi.dto.liveboard.CursorMoveDto;
import org.fitznet.fitznetapi.dto.liveboard.CursorRemoveDto;
import org.fitznet.fitznetapi.service.LiveBoardState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;

class LiveBoardControllerTest {

  @Mock private SimpMessagingTemplate messagingTemplate;
  @Mock private LiveBoardState boardState;
  @InjectMocks private LiveBoardController controller;

  private AutoCloseable mocks;

  @BeforeEach
  void setUp() {
    mocks = openMocks(this);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) mocks.close();
  }

  private Principal principal(String name) {
    return () -> name;
  }

  private SimpMessageHeaderAccessor headerAccessor(String sessionId) {
    SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
    when(accessor.getSessionId()).thenReturn(sessionId);
    return accessor;
  }

  // ---- join ----

  @Test
  void joinShouldRegisterSessionAndReturnBoardState() {
    List<BoardMessageDto> existing = List.of(
        BoardMessageDto.create("bob", 0.1, 0.2, "hey"));
    when(boardState.getMessages()).thenReturn(existing);

    BoardStateDto result = controller.join(headerAccessor("s1"), principal("alice"));

    verify(boardState).addSession("s1", "alice");
    assertEquals(1, result.getMessages().size());
    assertEquals("hey", result.getMessages().get(0).getContent());
  }

  // ---- cursor ----

  @Test
  void cursorShouldBroadcastWithUsernameFromPrincipal() {
    CursorMoveDto move = new CursorMoveDto(null, 0.3, 0.7);

    controller.cursor(move, principal("alice"));

    ArgumentCaptor<CursorMoveDto> captor = ArgumentCaptor.forClass(CursorMoveDto.class);
    verify(messagingTemplate).convertAndSend(eq("/topic/board/cursors"), captor.capture());
    assertEquals("alice", captor.getValue().getUsername());
    assertEquals(0.3, captor.getValue().getXRatio());
    assertEquals(0.7, captor.getValue().getYRatio());
  }

  // ---- message ----

  @Test
  void messageShouldAssignIdAndBroadcast() {
    BoardMessageDto incoming = new BoardMessageDto(null, null, 0.5, 0.5, "hello world", null);

    controller.message(incoming, principal("alice"));

    ArgumentCaptor<BoardMessageDto> captor = ArgumentCaptor.forClass(BoardMessageDto.class);
    verify(boardState).addMessage(captor.capture());
    verify(messagingTemplate).convertAndSend(eq("/topic/board/messages"), captor.capture());

    BoardMessageDto stored = captor.getAllValues().get(0);
    assertEquals("alice", stored.getUsername());
    assertEquals("hello world", stored.getContent());
    assertNotNull(stored.getId());
    assertNotNull(stored.getPostedAt());
  }

  // ---- disconnect ----

  @Test
  void disconnectShouldBroadcastCursorRemoveForKnownSession() {
    when(boardState.removeSession("s1")).thenReturn("alice");
    when(boardState.clearBoardIfEmpty()).thenReturn(false);

    SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
    when(event.getSessionId()).thenReturn("s1");

    controller.handleDisconnect(event);

    ArgumentCaptor<CursorRemoveDto> captor = ArgumentCaptor.forClass(CursorRemoveDto.class);
    verify(messagingTemplate).convertAndSend(
        eq("/topic/board/cursor-remove"), captor.capture());
    assertEquals("alice", captor.getValue().getUsername());
  }

  @Test
  void disconnectShouldBroadcastBoardClearedWhenLastUserLeaves() {
    when(boardState.removeSession("s1")).thenReturn("alice");
    when(boardState.clearBoardIfEmpty()).thenReturn(true);

    SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
    when(event.getSessionId()).thenReturn("s1");

    controller.handleDisconnect(event);

    verify(messagingTemplate).convertAndSend(eq("/topic/board/cleared"), anyString());
  }

  @Test
  void disconnectShouldNotBroadcastBoardClearedWhenUsersRemain() {
    when(boardState.removeSession("s1")).thenReturn("alice");
    when(boardState.clearBoardIfEmpty()).thenReturn(false);

    SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
    when(event.getSessionId()).thenReturn("s1");

    controller.handleDisconnect(event);

    verify(messagingTemplate, never()).convertAndSend(
        eq("/topic/board/cleared"), anyString());
  }

  @Test
  void disconnectForUnknownSessionShouldDoNothing() {
    when(boardState.removeSession("unknown")).thenReturn(null);

    SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
    when(event.getSessionId()).thenReturn("unknown");

    controller.handleDisconnect(event);

    verifyNoInteractions(messagingTemplate);
  }
}

