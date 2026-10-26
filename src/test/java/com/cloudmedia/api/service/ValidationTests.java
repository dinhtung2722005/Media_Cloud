package com.cloudmedia.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.repository.UserRepository;
import com.cloudmedia.api.security.AuthRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class ValidationTests {
  @Test
  void registrationRejectsBlankNamesAndInvalidPasswordsBeforeDatabase() {
    var users = mock(UserRepository.class);
    var service = new UserService(users, mock(AuthSessionService.class));
    assertThatThrownBy(() -> service.registerUser("   ", null, "password"))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> service.registerUser("alice", null, ""))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> service.registerUser("a".repeat(51), null, "password"))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> service.registerUser("alice", null, "ế".repeat(25)))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> service.registerUser("alice", "invalid-email", "password"))
        .isInstanceOf(DomainException.class);
    verifyNoInteractions(users);
    assertThat(InputRules.password(" pass word ", true)).isEqualTo(" pass word ");
  }

  @Test
  void authenticationLimitWorksWhenRedisIsUnavailable() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(
            any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),
            any(Object[].class)))
        .thenThrow(new IllegalStateException("offline"));
    var limiter = new AuthRateLimiter(redis);
    for (int i = 0; i < 5; i++) limiter.check("register", "one-address", "user");
    assertThatThrownBy(() -> limiter.check("register", "one-address", "user"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.status().value()).isEqualTo(429));
    assertThatCode(() -> limiter.check("register", "other-address", "user"))
        .doesNotThrowAnyException();
  }
}
