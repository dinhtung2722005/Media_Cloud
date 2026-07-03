package com.cloudmedia.api.service;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.cloudmedia.api.entity.User;
import com.cloudmedia.api.repository.UserRepository;
import com.cloudmedia.api.security.JwtUtils;
@Service
public class UserService{
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    public UserService(UserRepository userRepository, JwtUtils jwtUtils) {
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
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
    public String loginUser(String username, String rawPassword) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isPresent() || !passwordEncoder.matches(rawPassword, userOpt.get().getPasswordHash())) {
            User user = userOpt.get();
            return jwtUtils.generateJwtToken(user);
        }
        return null;
        }

}