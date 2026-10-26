package com.cloudmedia.api.config;

import com.cloudmedia.api.security.SessionRevokedEvent;
import com.cloudmedia.api.service.JobEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Redis broadcasts to each node's local user broker; SQL polling recovers missed events. */
@Component
public class ClusterEvents {
  private static final String CHANNEL = "media-cloud:events:v1";

  public record Envelope(
      String origin, String kind, String userId, String sessionId, Map<String, Object> payload) {}

  private final String origin = UUID.randomUUID().toString();
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final SimpMessagingTemplate sockets;
  private final AuthenticatedWebSocketSessions sessions;
  private final RedisMessageListenerContainer listener;
  private final boolean enabled;

  public ClusterEvents(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      SimpMessagingTemplate sockets,
      AuthenticatedWebSocketSessions sessions,
      RedisConnectionFactory factory,
      @Value("${app.events.redis-enabled:true}") boolean enabled) {
    this.redis = redis;
    this.mapper = mapper;
    this.sockets = sockets;
    this.sessions = sessions;
    this.enabled = enabled;
    listener = new RedisMessageListenerContainer();
    listener.setConnectionFactory(factory);
    listener.setRecoveryInterval(5000);
    listener.addMessageListener(
        (message, pattern) -> receive(message.getBody()), new ChannelTopic(CHANNEL));
    listener
        .afterPropertiesSet(); // Owned lifecycle: Redis being offline must not block application
                               // startup.
  }

  @Scheduled(fixedDelay = 5000)
  public void reconnect() {
    if (enabled && !listener.isRunning())
      try {
        listener.start();
      } catch (RuntimeException unavailable) {
        /* Reconnect on the next tick. */
      }
  }

  @EventListener
  public void progress(JobEvents.Notice event) {
    sockets.convertAndSendToUser(event.userId(), "/queue/progress", event.payload());
    broadcast(new Envelope(origin, "progress", event.userId(), null, event.payload()));
  }

  @EventListener
  public void revoke(SessionRevokedEvent event) {
    broadcast(new Envelope(origin, "revoke", null, event.sessionId(), Map.of()));
  }

  private void broadcast(Envelope event) {
    if (enabled)
      try {
        redis.convertAndSend(CHANNEL, mapper.writeValueAsString(event));
      } catch (Exception unavailable) {
        /* Durable status and session validity remain in SQL. */
      }
  }

  void receive(byte[] data) {
    try {
      Envelope event = mapper.readValue(data, Envelope.class);
      if (origin.equals(event.origin())) return;
      if ("revoke".equals(event.kind()) && event.sessionId() != null)
        sessions.onSessionRevoked(new SessionRevokedEvent(event.sessionId()));
      else if ("progress".equals(event.kind()) && event.userId() != null && event.payload() != null)
        sockets.convertAndSendToUser(event.userId(), "/queue/progress", event.payload());
    } catch (Exception malformed) {
      /* Pub/sub contains notifications only; never execute jobs from this channel. */
    }
  }

  @PreDestroy
  public void close() throws Exception {
    listener.destroy();
  }
}
