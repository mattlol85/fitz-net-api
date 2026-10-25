package org.fitznet.fitznetapi.config;

import org.fitznet.fitznetapi.tracing.TraceContext;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LiveBoardChannelInterceptor implements ChannelInterceptor {

  private static final Logger log = LoggerFactory.getLogger(LiveBoardChannelInterceptor.class);

  @Autowired private JwtUtil jwtUtil;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

    if (accessor == null) {
      return message;
    }
    traceMessage(accessor);

    if (!StompCommand.CONNECT.equals(accessor.getCommand())) {
      return message;
    }

    String authHeader = accessor.getFirstNativeHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      log.warn("LiveBoard STOMP CONNECT rejected: missing or invalid Authorization header");
      throw new MessagingException("Unauthorized: missing or invalid Authorization header");
    }

    String token = authHeader.substring(7);
    String username;
    try {
      username = jwtUtil.extractUsername(token);
    } catch (Exception e) {
      log.warn("LiveBoard STOMP CONNECT rejected: could not extract username from token");
      throw new MessagingException("Unauthorized: invalid token");
    }

    if (!jwtUtil.validateToken(token)) {
      log.warn("LiveBoard STOMP CONNECT rejected: token invalid or expired for user {}", username);
      throw new MessagingException("Unauthorized: token invalid or expired");
    }

    UsernamePasswordAuthenticationToken principal =
        new UsernamePasswordAuthenticationToken(username, null, List.of());
    accessor.setUser(principal);
    log.debug("LiveBoard STOMP CONNECT authenticated for user: {}", username);

    return message;
  }

  @Override
  public void afterSendCompletion(
      Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
    TraceContext.clear();
  }

  /**
   * Puts the connection's correlation id in the MDC. The id is taken from the CONNECT frame's
   * {@code X-Correlation-ID} native header and remembered in the WebSocket session so later
   * frames on the same connection log under it.
   */
  private void traceMessage(StompHeaderAccessor accessor) {
    Map<String, Object> session = accessor.getSessionAttributes();
    String correlationId = null;
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
      correlationId =
          TraceContext.idOrNew(accessor.getFirstNativeHeader(TraceContext.CORRELATION_ID_HEADER));
      if (session != null) {
        session.put(TraceContext.MDC_CORRELATION_ID, correlationId);
      }
    } else if (session != null) {
      correlationId = (String) session.get(TraceContext.MDC_CORRELATION_ID);
    }
    if (correlationId != null) {
      TraceContext.put(correlationId, correlationId, "stomp");
    }
  }
}
