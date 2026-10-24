package com.cloudmedia.api.service;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.cloudmedia.api.entity.User;
import com.cloudmedia.api.repository.UserRepository;
import java.util.Map;
@Service
public class UserService{
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService sessions;
    public UserService(UserRepository userRepository, AuthSessionService sessions) {
        this.userRepository = userRepository;
        this.sessions = sessions;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }
    public User registerUser(String username, String email, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("Tên đăng nhập đã tồn tại!");
        }

        User newUser = new User();
        newUser.setId(UUID.randomUUID().toString());
        newUser.setUsername(username);
        newUser.setEmail(email);
        newUser.setPasswordHash(passwordEncoder.encode(rawPassword)); 
        newUser.setCreatedAt(LocalDateTime.now());

        return userRepository.save(newUser);
    }
    public Map<String, String> loginUser(String username, String rawPassword) {
    Optional<User> userOpt = userRepository.findByUsername(username);
    
    // Kiểm tra đúng username VÀ password
    if (userOpt.isPresent() && passwordEncoder.matches(rawPassword, userOpt.get().getPasswordHash())) {
        User user = userOpt.get();
        
        // 1. Tạo cặp Token từ JwtUtils
        return sessions.issueTokens(user);
    }
    
    return null; // Đăng nhập thất bại
}

}
