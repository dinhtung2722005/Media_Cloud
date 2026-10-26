package com.cloudmedia.api.cache;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.*;

@EnabledIfEnvironmentVariable(named = "RUN_REDIS_IT", matches = "true")
class RedisCacheIntegrationTests {
  LettuceConnectionFactory connection;
  StringRedisTemplate redis;
  DriveCache cache;
  String owner = "redis-it-" + UUID.randomUUID();
  String other = "redis-it-" + UUID.randomUUID();

  @BeforeEach
  void setup() {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(
            System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1"),
            Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
    config.setDatabase(15); // Separate logical database; tests touch only random, owned prefixes.
    String password = System.getenv("REDIS_PASSWORD");
    if (password != null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
    config.setUsername(System.getenv().getOrDefault("REDIS_USERNAME", "default"));
    connection =
        new LettuceConnectionFactory(
            config,
            LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ofMillis(100))
                .build());
    connection.afterPropertiesSet();
    redis = new StringRedisTemplate(connection);
    cache = new DriveCache(redis, new ObjectMapper(), 60, true);
  }

  @AfterEach
  void cleanup() {
    if (redis != null) {
      for (String user : List.of(owner, other)) {
        String prefix = DriveCache.generationKey(user).replace(":revision", ":");
        List<String> keys = new ArrayList<>();
        try (var cursor =
            redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(100).build())) {
          cursor.forEachRemaining(
              key -> {
                if (key.startsWith(prefix)) keys.add(key);
              });
        }
        if (!keys.isEmpty()) redis.delete(keys);
      }
    }
    if (connection != null) connection.destroy();
  }

  @Test
  void repeatedReadsHitRedisAndRoundTripTypedUnicodeMetadata() {
    AtomicInteger reads = new AtomicInteger();
    cache.get(
        owner,
        null,
        () -> {
          reads.incrementAndGet();
          return DriveCacheTests.contents(owner, "Bình yên.mp4");
        });
    DriveContents hit =
        cache.get(
            owner,
            null,
            () -> {
              reads.incrementAndGet();
              throw new AssertionError("Cache miss");
            });
    assertThat(reads).hasValue(1);
    assertThat(hit.files().get(0).getTitle()).isEqualTo("Bình yên.mp4");
    assertThat(hit.files().get(0).getFileSize()).isEqualTo(9000000000L);
    assertThat(hit.folders().get(0).getUserId()).isEqualTo(owner);
    String revision = redis.opsForValue().get(DriveCache.generationKey(owner));
    assertThat(redis.getExpire(DriveCache.contentsKey(owner, null, revision))).isBetween(0L, 60L);
  }

  @Test
  void usersAndFoldersHaveSeparateCacheEntries() {
    cache.get(owner, null, () -> DriveCacheTests.contents(owner, "root.mp4"));
    cache.get(owner, "nested", () -> DriveCacheTests.contents(owner, "nested.mp4"));
    cache.get(other, null, () -> DriveCacheTests.contents(other, "private.mp4"));
    assertThat(cache.get(owner, null, () -> null).files().get(0).getTitle()).isEqualTo("root.mp4");
    assertThat(cache.get(owner, "nested", () -> null).files().get(0).getTitle())
        .isEqualTo("nested.mp4");
    assertThat(cache.get(other, null, () -> null).files().get(0).getUserId()).isEqualTo(other);
  }

  @Test
  void expiryReloadsMetadataFromTheDatabase() throws Exception {
    DriveCache shortLived = new DriveCache(redis, new ObjectMapper(), 1, true);
    shortLived.get(owner, null, () -> DriveCacheTests.contents(owner, "before.mp4"));
    Thread.sleep(1200);
    assertThat(
            shortLived
                .get(owner, null, () -> DriveCacheTests.contents(owner, "after.mp4"))
                .files()
                .get(0)
                .getTitle())
        .isEqualTo("after.mp4");
  }

  @Test
  void invalidationIsVisibleAcrossApplicationInstances() {
    cache.get(owner, null, () -> DriveCacheTests.contents(owner, "old.mp4"));
    new DriveCache(redis, new ObjectMapper(), 60, true).invalidateAfterCommit(owner);
    assertThat(
            cache
                .get(owner, null, () -> DriveCacheTests.contents(owner, "new.mp4"))
                .files()
                .get(0)
                .getTitle())
        .isEqualTo("new.mp4");
  }

  @Test
  void aConcurrentWritePreventsSlowReadFromRefillingStaleData() {
    cache.get(
        owner,
        null,
        () -> {
          cache.invalidateAfterCommit(owner);
          return DriveCacheTests.contents(owner, "stale.mp4");
        });
    assertThat(
            cache
                .get(owner, null, () -> DriveCacheTests.contents(owner, "fresh.mp4"))
                .files()
                .get(0)
                .getTitle())
        .isEqualTo("fresh.mp4");
  }

  @Test
  void evictedGenerationCannotReactivateAnOlderCacheEntry() {
    cache.get(owner, null, () -> DriveCacheTests.contents(owner, "old.mp4"));
    String old = redis.opsForValue().get(DriveCache.generationKey(owner));
    redis.delete(DriveCache.generationKey(owner));
    assertThat(
            cache
                .get(owner, null, () -> DriveCacheTests.contents(owner, "fresh.mp4"))
                .files()
                .get(0)
                .getTitle())
        .isEqualTo("fresh.mp4");
    assertThat(redis.opsForValue().get(DriveCache.generationKey(owner))).isNotEqualTo(old);
  }
}
