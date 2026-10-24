package com.cloudmedia.api.security;

import com.cloudmedia.api.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.security.Key;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtils {
    public static final long ACCESS_TOKEN_EXPIRATION = 15L * 60 * 1000;
    public static final long REFRESH_TOKEN_EXPIRATION = 7L * 24 * 60 * 60 * 1000;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @PostConstruct
    public void validateConfiguration() {
        try {
            getSigningKey();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("APP_JWT_SECRET phải là khóa Base64 chứa ít nhất 32 byte", exception);
        }
    }

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }

    public String generateAccessToken(User user, String sessionId) {
        return generateToken(user, sessionId, "access", ACCESS_TOKEN_EXPIRATION);
    }

    public String generateRefreshToken(User user, String sessionId) {
        return generateToken(user, sessionId, "refresh", REFRESH_TOKEN_EXPIRATION);
    }

    private String generateToken(User user, String sessionId, String type, long lifetime) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Thiếu mã phiên");
        return Jwts.builder().setId(UUID.randomUUID().toString()).setSubject(user.getId())
                .claim("sid", sessionId).claim("token_type", type).claim("username", user.getUsername())
                .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + lifetime))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256).compact();
    }

    private Claims claims(String token) {
        return Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token).getBody();
    }

    public String getUserIdFromJwtToken(String token) { return claims(token).getSubject(); }
    public String getSessionId(String token) { return claims(token).get("sid", String.class); }

    public boolean validateAccessToken(String token) { return validateToken(token, "access"); }
    public boolean validateRefreshToken(String token) { return validateToken(token, "refresh"); }

    private boolean validateToken(String token, String type) {
        try {
            Claims value = claims(token);
            return type.equals(value.get("token_type", String.class))
                    && value.getSubject() != null && value.get("sid", String.class) != null;
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }
}
