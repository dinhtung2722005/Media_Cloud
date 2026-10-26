package com.cloudmedia.api.security;

import com.cloudmedia.api.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuthRateLimiter {
  private static final DefaultRedisScript<Long> COUNT =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],60)"
              + " end;return n",
          Long.class);

  private record Bucket(long minute, int count) {}

  private final StringRedisTemplate redis;
  private final ConcurrentHashMap<String, Bucket> local = new ConcurrentHashMap<>();

  public AuthRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void check(String operation, String address, String username) {
    enforce(operation + ":ip:" + address, operation.equals("register") ? 5 : 60);
    if (operation.equals("login")) enforce(operation + ":account:" + username, 20);
  }

  private void enforce(String identity, int limit) {
    String key = "media-cloud:auth-rate:" + hash(identity);
    long count;
    try {
      Long value = redis.execute(COUNT, List.of(key));
      if (value == null) throw new IllegalStateException();
      count = value;
    } catch (RuntimeException unavailable) {
      if (local.size() > 10000)
        throw new DomainException(
            HttpStatus.TOO_MANY_REQUESTS,
            "AUTH_RATE_LIMIT",
            "Hệ thống đang nhận nhiều yêu cầu đăng nhập");
      long minute = Instant.now().getEpochSecond() / 60;
      count =
          local
              .compute(
                  key,
                  (ignored, old) ->
                      old == null || old.minute() != minute
                          ? new Bucket(minute, 1)
                          : new Bucket(minute, old.count() + 1))
              .count();
    }
    if (count > limit)
      throw new DomainException(
          HttpStatus.TOO_MANY_REQUESTS,
          "AUTH_RATE_LIMIT",
          "Bạn đã thử quá nhiều lần. Vui lòng chờ một phút");
  }

  @Scheduled(fixedDelay = 60000)
  public void cleanup() {
    long minute = Instant.now().getEpochSecond() / 60;
    local.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
  }

  private String hash(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
