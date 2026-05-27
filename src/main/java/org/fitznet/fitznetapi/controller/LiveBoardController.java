package org.fitznet.fitznetapi.controller;

import org.fitznet.fitznetapi.dto.liveboard.BoardMessageDto;
import org.fitznet.fitznetapi.dto.liveboard.BoardStateDto;
import org.fitznet.fitznetapi.dto.liveboard.CursorMoveDto;
import org.fitznet.fitznetapi.dto.liveboard.CursorRemoveDto;
import org.fitznet.fitznetapi.service.LiveBoardState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

@Controller
public class LiveBoardController {

  private static final Logger log = LoggerFactory.getLogger(LiveBoardController.class);

  @Autowired private SimpMessagingTemplate messagingTemplate;
  @Autowired private LiveBoardState boardState;

  /**
   * Called when a client subscribes to /app/board/join.
   * Registers the session and returns the current board state directly to the subscriber only.
   */
  @SubscribeMapping("/board/join")
  public BoardStateDto join(SimpMessageHeaderAccessor headerAccessor, Principal principal) {
    String sessionId = headerAccessor.getSessionId();
    String username = principal.getName();
    boardState.addSession(sessionId, username);
    log.info("User '{}' joined the Live Board (session: {})", username, sessionId);
    return new BoardStateDto(boardState.getMessages());
  }

  /**
   * Receives a cursor position from a client and broadcasts it to all subscribers.
   */
  @MessageMapping("/board/cursor")
  public void cursor(CursorMoveDto move, Principal principal) {
    move.setUsername(principal.getName());
    messagingTemplate.convertAndSend("/topic/board/cursors", move);
  }

  /**
   * Receives a message from a client, assigns a server-side UUID, stores it, and broadcasts.
   */
  @MessageMapping("/board/message")
  public void message(BoardMessageDto incoming, Principal principal) {
    BoardMessageDto message = BoardMessageDto.create(
        principal.getName(),
        incoming.getXRatio(),
        incoming.getYRatio(),
        incoming.getContent()
    );
    boardState.addMessage(message);
    messagingTemplate.convertAndSend("/topic/board/messages", message);
    log.debug("Message posted by '{}' at ({}, {})", principal.getName(),
        incoming.getXRatio(), incoming.getYRatio());
  }

  /**
   * Handles STOMP session disconnects. Removes the cursor, decrements user count,
   * and clears the board if no users remain.
   */
  @EventListener
  public void handleDisconnect(SessionDisconnectEvent event) {
    String sessionId = event.getSessionId();
    String username = boardState.removeSession(sessionId);

    if (username == null) {
      // Session was not registered with the Live Board — belongs to a different STOMP endpoint
      log.trace("STOMP disconnect for untracked session {} — not a Live Board session", sessionId);
      return;
    }

    log.info("User '{}' left the Live Board (session: {})", username, sessionId);
    messagingTemplate.convertAndSend("/topic/board/cursor-remove", new CursorRemoveDto(username));

    boolean cleared = boardState.clearBoardIfEmpty();
    if (cleared) {
      log.info("All users have left the Live Board — board cleared");
      messagingTemplate.convertAndSend("/topic/board/cleared", "{}");
    }
  }
}


