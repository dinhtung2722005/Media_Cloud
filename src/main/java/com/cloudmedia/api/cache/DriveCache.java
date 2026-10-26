package com.cloudmedia.api.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class DriveCache {
  private static final Logger log = LoggerFactory.getLogger(DriveCache.class);
  private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
  private static final DefaultRedisScript<Long> STORE =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
              return 1
          end
          return 0
          """,
          Long.class);
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final Duration ttl;
  private final Duration generationTtl;
  private final boolean enabled;
  private final AtomicLong bypassUntil = new AtomicLong();

  public DriveCache(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      @Value("${app.cache.drive-ttl-seconds:60}") long ttlSeconds,
      @Value("${app.cache.enabled:true}") boolean enabled) {
    if (ttlSeconds < 1 || ttlSeconds > 86400)
      throw new IllegalArgumentException("Cache TTL phải từ 1 đến 86400 giây");
    this.redis = redis;
    this.mapper = mapper.copy().deactivateDefaultTyping();
    this.ttl = Duration.ofSeconds(ttlSeconds);
    this.generationTtl = ttl.multipliedBy(2);
    this.enabled = enabled;
  }

  public DriveContents get(String userId, String folderId, Supplier<DriveContents> database) {
    if (!enabled || System.currentTimeMillis() < bypassUntil.get()) return database.get();
    String generation = null;
    String key = null;
    try {
      String versionKey = generationKey(userId);
      generation = redis.opsForValue().get(versionKey);
      if (generation == null) {
        String fresh = UUID.randomUUID().toString();
        generation =
            Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(versionKey, fresh, generationTtl))
                ? fresh
                : redis.opsForValue().get(versionKey);
      }
      if (generation != null) {
        key = contentsKey(userId, folderId, generation);
        String json = redis.opsForValue().get(key);
        if (json != null) {
          try {
            DriveContents contents = mapper.readValue(json, DriveContents.class);
            if (belongsTo(contents, userId)) return contents;
          } catch (java.io.IOException exception) {
            log.debug("Bỏ qua dữ liệu Redis cache không hợp lệ");
          }
          redis.delete(key);
        }
      }
    } catch (RuntimeException exception) {
      unavailable(exception, 1000);
      generation = null;
    }

    // Database failures must propagate; cache errors must never retry a database read.
    DriveContents contents = database.get();
    if (generation != null && key != null) {
      try {
        String json = mapper.writeValueAsString(contents);
        if (belongsTo(contents, userId)
            && json.getBytes(StandardCharsets.UTF_8).length <= MAX_ENTRY_BYTES) {
          // A slow read cannot refill an obsolete generation after a concurrent write.
          redis.execute(
              STORE,
              List.of(generationKey(userId), key),
              generation,
              json,
              Long.toString(ttl.toSeconds()));
        }
      } catch (Exception exception) {
        unavailable(exception, 1000);
      }
    }
    return contents;
  }

  public void invalidateAfterCommit(String userId) {
    if (!enabled) return;
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              invalidate(userId);
            }
          });
    } else {
      invalidate(userId);
    }
  }

  private void invalidate(String userId) {
    try {
      // Random generations remain safe if Redis evicts or expires the version key.
      redis.opsForValue().set(generationKey(userId), UUID.randomUUID().toString(), generationTtl);
    } catch (RuntimeException exception) {
      // Do not reuse old cached data locally after a write whose invalidation was lost.
      unavailable(exception, ttl.toMillis());
    }
  }

  private void unavailable(Exception exception, long backoffMillis) {
    long now = System.currentTimeMillis();
    long previous = bypassUntil.getAndAccumulate(now + backoffMillis, Math::max);
    if (previous <= now)
      log.warn("Bỏ qua Redis cache; đọc SQL Server ({}).", exception.getClass().getSimpleName());
  }

  private boolean belongsTo(DriveContents contents, String userId) {
    return contents != null
        && contents.folders().stream().allMatch(folder -> userId.equals(folder.getUserId()))
        && contents.files().stream().allMatch(file -> userId.equals(file.getUserId()));
  }

  static String generationKey(String userId) {
    return prefix(userId) + ":revision";
  }

  static String contentsKey(String userId, String folderId, String generation) {
    return prefix(userId)
        + ":"
        + generation
        + ":"
        + (folderId == null || folderId.isBlank() ? "root" : encode(folderId));
  }

  private static String prefix(String userId) {
    return "media-cloud:drive:v1:{" + encode(userId) + "}";
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
