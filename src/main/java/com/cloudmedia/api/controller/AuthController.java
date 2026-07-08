package com.cloudmedia.api.controller;

import com.cloudmedia.api.entity.User;
import com.cloudmedia.api.repository.UserRepository;
import com.cloudmedia.api.security.JwtUtils;
import com.cloudmedia.api.service.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserService userService, UserRepository userRepository, JwtUtils jwtUtils) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> request) {
        try {
            User newUser = userService.registerUser(
                    request.get("username"),
                    request.get("email"),
                    request.get("password")
            );
            return ResponseEntity.ok(Map.of("message", "Đăng ký thành công!", "userId", newUser.getId()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        String token = userService.loginUser(username, password);        
        if (token != null) {
            
            // TẠO COOKIE BẢO MẬT VÀ DÁN TEM SAMESITE="Lax"
            ResponseCookie springCookie = ResponseCookie.from("jwt_token", token)
                    .httpOnly(true)
                    .secure(false) // localhost đang dùng http nên để false
                    .path("/")
                    .maxAge(24 * 60 * 60)
                    .sameSite("Lax") // <--- CHÌA KHÓA ĐỂ TRÌNH DUYỆT CHỊU NHẬN COOKIE
                    .build();

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, springCookie.toString())
                    .body(Map.of(
                            "message", "Đăng nhập thành công!",
                            "username", username
                    ));
        }
        
        return ResponseEntity.status(401).body(Map.of("error", "Sai tên đăng nhập hoặc mật khẩu!"));
    }

    // THÊM API ĐĂNG XUẤT ĐỂ XÓA COOKIE
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        ResponseCookie cleanCookie = ResponseCookie.from("jwt_token", "")
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(0) // Set maxAge = 0 để bắt trình duyệt hủy Cookie
                .sameSite("Lax")
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleanCookie.toString())
                .body(Map.of("message", "Đã đăng xuất an toàn"));
    }
}