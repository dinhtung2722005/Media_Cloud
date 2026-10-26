package com.cloudmedia.api.config;

import static org.mockito.Mockito.*;

import com.cloudmedia.api.security.SessionRevokedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ClusterEventsTests {
  @Test
  void remoteProgressAndRevocationReachLocalSocketsWithoutRebroadcast() throws Exception {
    var redis = mock(StringRedisTemplate.class);
    var sockets = mock(SimpMessagingTemplate.class);
    var sessions = mock(AuthenticatedWebSocketSessions.class);
    var mapper = new ObjectMapper();
    var bridge =
        new ClusterEvents(
            redis, mapper, sockets, sessions, mock(RedisConnectionFactory.class), false);
    try {
      var payload = Map.<String, Object>of("taskId", "job", "status", "COMPLETED");
      bridge.receive(
          mapper.writeValueAsBytes(
              new ClusterEvents.Envelope("other-node", "progress", "owner", null, payload)));
      verify(sockets).convertAndSendToUser("owner", "/queue/progress", payload);
      bridge.receive(
          mapper.writeValueAsBytes(
              new ClusterEvents.Envelope("other-node", "revoke", null, "session", Map.of())));
      verify(sessions).onSessionRevoked(new SessionRevokedEvent("session"));
      verifyNoInteractions(redis);
    } finally {
      bridge.close();
    }
  }
}
