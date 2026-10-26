package com.cloudmedia.api.service;

import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.entity.User;
import com.cloudmedia.api.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthSessionService sessions;

  public UserService(UserRepository userRepository, AuthSessionService sessions) {
    this.userRepository = userRepository;
    this.sessions = sessions;
    this.passwordEncoder = new BCryptPasswordEncoder();
  }

  public User registerUser(String username, String email, String rawPassword) {
    username = InputRules.username(username);
    rawPassword = InputRules.password(rawPassword, true);
    if (email != null) {
      email = email.trim();
      if (email.isEmpty()) email = null;
      else if (email.length() > 100 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
        throw DomainException.invalid("Email không hợp lệ");
    }
    if (userRepository.existsByUsername(username)) {
      throw DomainException.conflict("ACCOUNT_EXISTS", "Tên đăng nhập đã tồn tại");
    }

    User newUser = new User();
    newUser.setId(UUID.randomUUID().toString());
    newUser.setUsername(username);
    newUser.setEmail(email);
    newUser.setPasswordHash(passwordEncoder.encode(rawPassword));
    newUser.setCreatedAt(LocalDateTime.now());

    try {
      return userRepository.saveAndFlush(newUser);
    } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
      throw DomainException.conflict("ACCOUNT_EXISTS", "Tên đăng nhập hoặc email đã được sử dụng");
    }
  }

  public Map<String, String> loginUser(String username, String rawPassword) {
    username = InputRules.username(username);
    rawPassword = InputRules.password(rawPassword, false);
    Optional<User> userOpt = userRepository.findByUsername(username);

    // Kiểm tra đúng username VÀ password
    if (userOpt.isPresent()
        && passwordEncoder.matches(rawPassword, userOpt.get().getPasswordHash())) {
      User user = userOpt.get();

      // 1. Tạo cặp Token từ JwtUtils
      return sessions.issueTokens(user);
    }

    return null; // Đăng nhập thất bại
  }
}
