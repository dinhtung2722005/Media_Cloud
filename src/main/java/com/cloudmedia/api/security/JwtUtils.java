package com.cloudmedia.api.security;

import java.security.Key;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cloudmedia.api.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtils {

    // Đọc chữ ký bí mật từ application.properties
    @Value("${app.jwt.secret}")
    private String jwtSecret;

    // Đặt hằng số thời gian cho dễ quản lý (Tính bằng mili-giây)
    private final long ACCESS_TOKEN_EXPIRATION = 900000L;       // 15 phút
    private final long REFRESH_TOKEN_EXPIRATION = 604800000L;   // 7 ngày

    // Hàm lấy chữ ký bí mật
    private Key getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // 1. Tạo Access Token (Sống 15 phút)
    public String generateAccessToken(User user) {
        return Jwts.builder()
                .setSubject(user.getId()) // Đưa ID vào Subject để đồng nhất
                .claim("username", user.getUsername()) // Lưu username vào claim
                .claim("email", user.getEmail())       // Lưu email vào claim
                .setIssuedAt(new Date())
                .setExpiration(new Date((new Date()).getTime() + ACCESS_TOKEN_EXPIRATION))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256) // Đã sửa key() thành getSigningKey()
                .compact();
    }
    
    // 2. Tạo Refresh Token (Sống 7 ngày)
    public String generateRefreshToken(User user) {
        return Jwts.builder()
                .setSubject(user.getId()) // Đưa ID vào Subject
                .claim("username", user.getUsername()) // Cần thiết để sau này cấp lại Access Token
                .setIssuedAt(new Date())
                .setExpiration(new Date((new Date()).getTime() + REFRESH_TOKEN_EXPIRATION))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256) // Đã sửa key() thành getSigningKey()
                .compact();
    }


    public String getUserIdFromJwtToken(String token) {
        return Jwts.parserBuilder().setSigningKey(getSigningKey()).build()
                .parseClaimsJws(token).getBody().getSubject();
    }

    public String getUsernameFromJwtToken(String token) {
        Claims claims = Jwts.parserBuilder().setSigningKey(getSigningKey()).build()
                .parseClaimsJws(token).getBody();
        return claims.get("username", String.class);
    }

    public Date getExpirationDateFromJwtToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getExpiration();
    }


    public boolean validateJwtToken(String authToken) {
        try {
            Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(authToken);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            System.err.println("Token không hợp lệ: " + e.getMessage());
        }
        return false;
    }
}