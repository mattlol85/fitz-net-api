package org.fitznet.fitznetapi.grpc;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.Set;
import org.fitznet.fitznetapi.config.AdminApiKeyFilter;
import org.fitznet.fitznetapi.config.JwtAuthenticationFilter;
import org.fitznet.fitznetapi.grpc.proto.InfoServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.NodeServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.UserServiceGrpc;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * gRPC counterpart of {@link AdminApiKeyFilter} + {@link JwtAuthenticationFilter} and the
 * permitAll/authenticated rules in {@code SecurityConfig}. Reads {@code x-admin-key} or
 * {@code authorization: Bearer <jwt>} metadata, rejects unauthenticated calls to non-public
 * Fitz-Net methods, and exposes the authentication through {@link SecurityContextHolder} for every
 * listener callback so {@code @PreAuthorize} on the delegated controllers applies unchanged.
 * Sits inside the exception handler interceptor so a rejection maps to UNAUTHENTICATED.
 */
@Component
@GlobalServerInterceptor
@Order(GrpcExceptionHandlerInterceptor.ORDER + 10)
public class GrpcAuthInterceptor implements ServerInterceptor {

  static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  static final Metadata.Key<String> ADMIN_KEY =
      Metadata.Key.of(AdminApiKeyFilter.HEADER.toLowerCase(), Metadata.ASCII_STRING_MARSHALLER);

  /** Only Fitz-Net services are guarded; grpc.health / grpc.reflection stay open. */
  private static final String GUARDED_PREFIX = "fitznet.api.";

  /** Mirrors SecurityConfig's permitAll() endpoints. */
  private static final Set<String> PUBLIC_METHODS =
      Set.of(
          InfoServiceGrpc.getGetInfoMethod().getFullMethodName(),
          UserServiceGrpc.getCreateUserMethod().getFullMethodName(),
          UserServiceGrpc.getLoginMethod().getFullMethodName(),
          NodeServiceGrpc.getRegisterNodeMethod().getFullMethodName(),
          NodeServiceGrpc.getHeartbeatMethod().getFullMethodName(),
          NodeServiceGrpc.getListNodesMethod().getFullMethodName(),
          NodeServiceGrpc.getDeleteNodeMethod().getFullMethodName());

  private final AdminApiKeyFilter adminApiKeyFilter;
  private final JwtAuthenticationFilter jwtAuthenticationFilter;

  public GrpcAuthInterceptor(
      AdminApiKeyFilter adminApiKeyFilter, JwtAuthenticationFilter jwtAuthenticationFilter) {
    this.adminApiKeyFilter = adminApiKeyFilter;
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    // Same precedence as the servlet chain: the admin key filter runs before the JWT filter.
    Authentication auth = adminApiKeyFilter.authenticate(headers.get(ADMIN_KEY));
    if (auth == null) {
      auth = jwtAuthenticationFilter.authenticate(headers.get(AUTHORIZATION));
    }
    if (auth == null && requiresAuthentication(call.getMethodDescriptor().getFullMethodName())) {
      throw new AuthenticationCredentialsNotFoundException("Unauthorized");
    }

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    try {
      return new SecurityContextListener<>(next.startCall(call, headers), context);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  static boolean requiresAuthentication(String fullMethodName) {
    return fullMethodName.startsWith(GUARDED_PREFIX) && !PUBLIC_METHODS.contains(fullMethodName);
  }

  /** gRPC may run each callback on a different thread, so bind the context around every one. */
  private static final class SecurityContextListener<ReqT>
      extends SimpleForwardingServerCallListener<ReqT> {

    private final SecurityContext context;

    SecurityContextListener(ServerCall.Listener<ReqT> delegate, SecurityContext context) {
      super(delegate);
      this.context = context;
    }

    @Override
    public void onMessage(ReqT message) {
      run(() -> super.onMessage(message));
    }

    @Override
    public void onHalfClose() {
      run(super::onHalfClose);
    }

    @Override
    public void onReady() {
      run(super::onReady);
    }

    @Override
    public void onCancel() {
      run(super::onCancel);
    }

    @Override
    public void onComplete() {
      run(super::onComplete);
    }

    private void run(Runnable callback) {
      SecurityContextHolder.setContext(context);
      try {
        callback.run();
      } finally {
        SecurityContextHolder.clearContext();
      }
    }
  }
}
