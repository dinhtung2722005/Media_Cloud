package com.cloudmedia.api.cache;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmedia.api.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.support.*;

class DriveCacheTests {
  StringRedisTemplate redis;
  ValueOperations<String, String> values;
  DriveCache cache;
  ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setup() {
    redis = mock(StringRedisTemplate.class);
    values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    cache = new DriveCache(redis, mapper, 60, true);
  }

  @AfterEach
  void cleanup() {
    TransactionSynchronizationManager.clear();
  }

  static DriveContents contents(String user, String title) {
    return new DriveContents(
        List.of(new Folder("folder", "Thư mục", null, user)),
        List.of(
            new MediaFile(
                "file",
                title,
                "/storage/file.mp4",
                "mp4",
                9000000000L,
                null,
                user,
                "Ready",
                "Local")));
  }

  @Test
  void disabledCacheDoesNotContactRedis() {
    DriveCache disabled = new DriveCache(redis, mapper, 60, false);
    DriveContents result = contents("owner", "video.mp4");
    assertThat(disabled.get("owner", null, () -> result)).isSameAs(result);
    verify(redis, never()).opsForValue();
  }

  @Test
  void unavailableRedisReadsDatabaseOnceAndDoesNotHideDatabaseFailure() {
    when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("Unavailable"));
    AtomicInteger reads = new AtomicInteger();
    DriveContents result =
        cache.get(
            "owner",
            null,
            () -> {
              reads.incrementAndGet();
              return contents("owner", "video.mp4");
            });
    assertThat(reads).hasValue(1);
    assertThat(result.files()).hasSize(1);
    assertThatThrownBy(
            () ->
                cache.get(
                    "owner",
                    null,
                    () -> {
                      throw new IllegalStateException("SQL unavailable");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SQL unavailable");
  }

  @Test
  void malformedCacheIsReplacedFromDatabase() {
    when(values.get(DriveCache.generationKey("owner"))).thenReturn("revision");
    String key = DriveCache.contentsKey("owner", null, "revision");
    when(values.get(key)).thenReturn("{broken json");
    DriveContents result = contents("owner", "fresh.mp4");
    assertThat(cache.get("owner", null, () -> result)).isSameAs(result);
    verify(redis).delete(key);
  }

  @Test
  void cachedMetadataForAnotherOwnerIsNeverReturned() throws Exception {
    when(values.get(DriveCache.generationKey("owner"))).thenReturn("revision");
    String key = DriveCache.contentsKey("owner", null, "revision");
    when(values.get(key)).thenReturn(mapper.writeValueAsString(contents("other", "private.mp4")));
    DriveContents result = contents("owner", "mine.mp4");
    assertThat(cache.get("owner", null, () -> result)).isSameAs(result);
    verify(redis).delete(key);
  }

  @Test
  void transactionCommitInvalidatesOnlyAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    cache.invalidateAfterCommit("owner");
    verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(values)
        .set(eq(DriveCache.generationKey("owner")), anyString(), eq(Duration.ofSeconds(120)));
  }

  @Test
  void transactionRollbackKeepsTheExistingGeneration() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    cache.invalidateAfterCommit("owner");
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    verify(values, never()).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void failedInvalidationBypassesOldCachedMetadataDuringItsRemainingLifetime() {
    doThrow(new RedisConnectionFailureException("Unavailable"))
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    cache.invalidateAfterCommit("owner");
    DriveContents fresh = contents("owner", "renamed.mp4");
    assertThat(cache.get("owner", null, () -> fresh)).isSameAs(fresh);
    verify(values, never()).get(anyString());
  }
}
