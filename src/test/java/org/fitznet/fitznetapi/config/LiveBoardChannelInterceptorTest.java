package org.fitznet.fitznetapi.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

import org.fitznet.fitznetapi.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

class LiveBoardChannelInterceptorTest {

  @Mock private JwtUtil jwtUtil;
  @InjectMocks private LiveBoardChannelInterceptor interceptor;

  private AutoCloseable mocks;
  private MessageChannel channel;

  @BeforeEach
  void setUp() {
    mocks = openMocks(this);
    channel = mock(MessageChannel.class);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) mocks.close();
  }

  private Message<?> buildConnectMessage(String authHeader) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    if (authHeader != null) {
      accessor.addNativeHeader("Authorization", authHeader);
    }
    accessor.setSessionId("test-session");
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  @Test
  void validTokenShouldSetUserPrincipal() {
    when(jwtUtil.extractUsername("valid-token")).thenReturn("alice");
    when(jwtUtil.validateToken("valid-token")).thenReturn(true);

    Message<?> msg = buildConnectMessage("Bearer valid-token");
    Message<?> result = interceptor.preSend(msg, channel);

    assertNotNull(result);
    StompHeaderAccessor resultAccessor =
        StompHeaderAccessor.wrap(result);
    Principal user = resultAccessor.getUser();
    assertNotNull(user);
    assertEquals("alice", user.getName());
  }

  @Test
  void missingAuthHeaderShouldThrowMessagingException() {
    Message<?> msg = buildConnectMessage(null);
    assertThrows(MessagingException.class, () -> interceptor.preSend(msg, channel));
  }

  @Test
  void headerWithoutBearerPrefixShouldThrowMessagingException() {
    Message<?> msg = buildConnectMessage("just-a-token");
    assertThrows(MessagingException.class, () -> interceptor.preSend(msg, channel));
  }

  @Test
  void invalidTokenShouldThrowMessagingException() {
    when(jwtUtil.extractUsername("bad-token")).thenReturn("alice");
    when(jwtUtil.validateToken("bad-token")).thenReturn(false);

    Message<?> msg = buildConnectMessage("Bearer bad-token");
    assertThrows(MessagingException.class, () -> interceptor.preSend(msg, channel));
  }

  @Test
  void malformedTokenShouldThrowMessagingException() {
    when(jwtUtil.extractUsername("malformed")).thenThrow(new RuntimeException("parse error"));

    Message<?> msg = buildConnectMessage("Bearer malformed");
    assertThrows(MessagingException.class, () -> interceptor.preSend(msg, channel));
  }

  @Test
  void nonConnectCommandShouldPassThroughUnmodified() {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setSessionId("test-session");
    accessor.setLeaveMutable(true);
    Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    Message<?> result = interceptor.preSend(msg, channel);
    assertNotNull(result);
    // No JwtUtil interactions for non-CONNECT frames
    verifyNoInteractions(jwtUtil);
  }
}

