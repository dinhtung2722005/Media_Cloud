package com.cloudmedia.api.controller;

import com.cloudmedia.api.entity.User;
import com.cloudmedia.api.repository.UserRepository;
import com.cloudmedia.api.security.JwtUtils;
import com.cloudmedia.api.service.AuthSessionService;
import com.cloudmedia.api.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final UserService userService;
  private final UserRepository userRepository;
  private final JwtUtils jwtUtils;
  private final AuthSessionService sessions;
  private final com.cloudmedia.api.security.AuthRateLimiter limiter;

  @Value("${app.security.secure-cookies:false}")
  private boolean secureCookies;

  public AuthController(
      UserService userService,
      UserRepository userRepository,
      JwtUtils jwtUtils,
      AuthSessionService sessions,
      com.cloudmedia.api.security.AuthRateLimiter limiter) {
    this.userService = userService;
    this.userRepository = userRepository;
    this.jwtUtils = jwtUtils;
    this.sessions = sessions;
    this.limiter = limiter;
  }

  public record RegisterRequest(String username, String email, String password) {
    public RegisterRequest {
      username = com.cloudmedia.api.domain.InputRules.username(username);
      password = com.cloudmedia.api.domain.InputRules.password(password, true);
    }
  }

  public record LoginRequest(String username, String password) {
    public LoginRequest {
      username = com.cloudmedia.api.domain.InputRules.username(username);
      password = com.cloudmedia.api.domain.InputRules.password(password, false);
    }
  }

  @PostMapping("/register")
  public ResponseEntity<?> register(@RequestBody RegisterRequest request, HttpServletRequest http) {
    limiter.check("register", http.getRemoteAddr(), String.valueOf(request.username()));
    User user = userService.registerUser(request.username(), request.email(), request.password());
    return ResponseEntity.ok(Map.of("message", "Đăng ký thành công!", "userId", user.getId()));
  }

  @PostMapping("/login")
  @Operation(
      summary = "Đăng nhập và cấp cookie HttpOnly",
      description =
          "Chạy endpoint này trước khi thử API được bảo vệ. Trình duyệt tự gửi cookie "
              + "access_token; response JSON không chứa JWT. Dùng tài khoản thực của bạn.")
  public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest http) {
    limiter.check("login", http.getRemoteAddr(), String.valueOf(request.username()));
    Map<String, String> tokens = userService.loginUser(request.username(), request.password());
    if (tokens == null) {
      return ResponseEntity.status(401).body(Map.of("error", "Sai tên đăng nhập hoặc mật khẩu!"));
    }
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE, cookie("access_token", tokens.get("accessToken"), "/", 15 * 60))
        .header(
            HttpHeaders.SET_COOKIE,
            cookie("refresh_token", tokens.get("refreshToken"), "/api/auth", 7 * 24 * 60 * 60))
        .header(HttpHeaders.SET_COOKIE, cookie("refresh_token", "", "/api/auth/refresh", 0))
        .header(HttpHeaders.SET_COOKIE, cookie("jwt_token", "", "/", 0))
        .body(Map.of("message", "Đăng nhập thành công!"));
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String access = readCookie(request, "access_token");
    if (access == null
        && request.getHeader(HttpHeaders.AUTHORIZATION) != null
        && request.getHeader(HttpHeaders.AUTHORIZATION).startsWith("Bearer ")) {
      access = request.getHeader(HttpHeaders.AUTHORIZATION).substring(7);
    }
    revokeToken(access, false);
    revokeToken(readCookie(request, "refresh_token"), true);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie("access_token", "", "/", 0))
        .header(HttpHeaders.SET_COOKIE, cookie("refresh_token", "", "/api/auth", 0))
        .header(HttpHeaders.SET_COOKIE, cookie("refresh_token", "", "/api/auth/refresh", 0))
        .header(HttpHeaders.SET_COOKIE, cookie("jwt_token", "", "/", 0))
        .body(Map.of("message", "Đã đăng xuất an toàn"));
  }

  @PostMapping("/refresh")
  @Operation(
      summary = "Gia hạn access token bằng refresh cookie",
      description =
          "Cần cookie refresh_token từ lần đăng nhập trước; cookie access_token được cập nhật.")
  public ResponseEntity<?> refreshToken(HttpServletRequest request) {
    String token = readCookie(request, "refresh_token");
    if (token != null && jwtUtils.validateRefreshToken(token) && sessions.isActive(token)) {
      return userRepository
          .findById(jwtUtils.getUserIdFromJwtToken(token))
          .<ResponseEntity<?>>map(
              user ->
                  ResponseEntity.ok()
                      .header(
                          HttpHeaders.SET_COOKIE,
                          cookie(
                              "access_token",
                              jwtUtils.generateAccessToken(user, jwtUtils.getSessionId(token)),
                              "/",
                              15 * 60))
                      .body(Map.of("message", "Đã cấp lại Access Token!")))
          .orElseGet(this::refreshRejected);
    }
    return refreshRejected();
  }

  private ResponseEntity<?> refreshRejected() {
    return ResponseEntity.status(401)
        .body(Map.of("error", "Phiên đăng nhập hết hạn, vui lòng đăng nhập lại"));
  }

  private void revokeToken(String token, boolean refresh) {
    if (token != null
        && (refresh ? jwtUtils.validateRefreshToken(token) : jwtUtils.validateAccessToken(token))) {
      sessions.revoke(token);
    }
  }

  private String readCookie(HttpServletRequest request, String name) {
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (name.equals(cookie.getName())) return cookie.getValue();
      }
    }
    return null;
  }

  private String cookie(String name, String value, String path, long maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(secureCookies)
        .path(path)
        .maxAge(maxAge)
        .sameSite("Lax")
        .build()
        .toString();
  }
}
