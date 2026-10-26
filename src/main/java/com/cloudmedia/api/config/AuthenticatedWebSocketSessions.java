package com.cloudmedia.api.config;

import com.cloudmedia.api.security.SessionRevokedEvent;
import com.cloudmedia.api.service.AuthSessionService;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

@Component
public class AuthenticatedWebSocketSessions implements WebSocketHandlerDecoratorFactory {
  private final AuthSessionService sessions;
  private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();

  public AuthenticatedWebSocketSessions(AuthSessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  public WebSocketHandler decorate(WebSocketHandler delegate) {
    return new WebSocketHandlerDecorator(delegate) {
      @Override
      public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        if (!(socket.getPrincipal() instanceof Authentication authentication)
            || !(authentication.getDetails() instanceof String sessionId)) {
          socket.close(CloseStatus.POLICY_VIOLATION);
          return;
        }
        connections.put(
            socket.getId(), new Connection(sessionId, authentication.getName(), socket));
        // Register before checking the database to close the handshake/logout race.
        if (!sessions.isActive(sessionId, authentication.getName())) {
          connections.remove(socket.getId());
          socket.close(CloseStatus.POLICY_VIOLATION);
          return;
        }
        try {
          super.afterConnectionEstablished(socket);
        } catch (Exception exception) {
          connections.remove(socket.getId());
          throw exception;
        }
      }

      @Override
      public void afterConnectionClosed(WebSocketSession socket, CloseStatus status)
          throws Exception {
        connections.remove(socket.getId());
        super.afterConnectionClosed(socket, status);
      }
    };
  }

  @EventListener
  public void onSessionRevoked(SessionRevokedEvent event) {
    connections.forEach(
        (id, connection) -> {
          if (connection.sessionId().equals(event.sessionId())
              && connections.remove(id, connection)) {
            try {
              connection.socket().close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {
              /* The HTTP session is already revoked. */
            }
          }
        });
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
  public void closeExpired() {
    connections.forEach(
        (id, connection) -> {
          try {
            if (!sessions.isActive(connection.sessionId(), connection.userId()))
              onSessionRevoked(new SessionRevokedEvent(connection.sessionId()));
          } catch (RuntimeException unavailable) {
            onSessionRevoked(new SessionRevokedEvent(connection.sessionId()));
          }
        });
  }

  private record Connection(String sessionId, String userId, WebSocketSession socket) {}
}
