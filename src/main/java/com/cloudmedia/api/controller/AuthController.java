package com.cloudmedia.api.controller;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Optional;
import com.cloudmedia.api.entity.RevokedToken;
import com.cloudmedia.api.entity.User;
import com.cloudmedia.api.repository.RevokedTokenRepository;
import com.cloudmedia.api.repository.UserRepository;
import com.cloudmedia.api.security.JwtUtils;
import com.cloudmedia.api.service.UserService;
import java.util.Map;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;
    private final RevokedTokenRepository revokedTokenRepository;
    public AuthController(UserService userService, UserRepository userRepository, JwtUtils jwtUtils,RevokedTokenRepository revokedTokenRepository) {
        this.userService = userService;
        this.revokedTokenRepository = revokedTokenRepository;
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

        Map<String, String> tokens = userService.loginUser(username, password);
                if (tokens != null) {
            
                String accessToken = tokens.get("accessToken");
                String refreshToken = tokens.get("refreshToken");
                ResponseCookie accessCookie = ResponseCookie.from("access_token", accessToken)
                .httpOnly(true).secure(false).path("/").maxAge(15 * 60).sameSite("Lax").build();

        // Tạo Refresh Cookie (Path hẹp, chỉ gửi khi gọi API /refresh)
        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", refreshToken)
                .httpOnly(true).secure(false).path("/api/auth/refresh").maxAge(7 * 24 * 60 * 60).sameSite("Lax").build();

                return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString()) // Trả 2 thẻ header SET_COOKIE
                .body(Map.of("message", "Đăng nhập thành công!"));
        }
        
        return ResponseEntity.status(401).body(Map.of("error", "Sai tên đăng nhập hoặc mật khẩu!"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String jwt = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("jwt_token".equals(cookie.getName())) { // Tên cookie do bạn đặt
                    jwt = cookie.getValue();
                    break;
                }
            }
        }
        if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
            RevokedToken revokedToken = new RevokedToken();
            revokedToken.setToken(jwt);
            revokedToken.setExpirationDate(jwtUtils.getExpirationDateFromJwtToken(jwt));
            revokedTokenRepository.save(revokedToken);
            
        }
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
    @PostMapping("/refresh")
public ResponseEntity<?> refreshToken(HttpServletRequest request) {
    String refreshToken = null;
    
    // 1. Tìm Cookie có tên là "refresh_token"
    if (request.getCookies() != null) {
        for (Cookie cookie : request.getCookies()) {
            if ("refresh_token".equals(cookie.getName())) {
                refreshToken = cookie.getValue();
                break;
            }
        }
    }

    // 2. Kiểm tra tính hợp lệ
    if (refreshToken != null && jwtUtils.validateJwtToken(refreshToken)) {
        String username = jwtUtils.getUsernameFromJwtToken(refreshToken);
        Optional<User> userOpt = userRepository.findByUsername(username);     
        if (userOpt.isPresent()) {
            User user = userOpt.get();   
            String newAccessToken = jwtUtils.generateAccessToken(user);
        
        ResponseCookie newAccessCookie = ResponseCookie.from("access_token", newAccessToken)
                .httpOnly(true).secure(false).path("/").maxAge(15 * 60).sameSite("Lax").build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, newAccessCookie.toString())
                .body(Map.of("message", "Đã cấp lại Access Token!"));
    }
}

    return ResponseEntity.status(401).body(Map.of("error", "Refresh Token hết hạn, vui lòng đăng nhập lại"));
}
}