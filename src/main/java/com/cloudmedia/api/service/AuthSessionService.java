package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.AuthSession;
import com.cloudmedia.api.entity.User;
import com.cloudmedia.api.repository.AuthSessionRepository;
import com.cloudmedia.api.security.JwtUtils;
import com.cloudmedia.api.security.SessionRevokedEvent;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

@Service
public class AuthSessionService {
    private final AuthSessionRepository sessions;
    private final JwtUtils jwt;
    private final ApplicationEventPublisher events;

    public AuthSessionService(AuthSessionRepository sessions, JwtUtils jwt, ApplicationEventPublisher events) {
        this.sessions = sessions;
        this.jwt = jwt;
        this.events = events;
    }

    public Map<String, String> issueTokens(User user) {
        String sessionId = UUID.randomUUID().toString();
        sessions.saveAndFlush(new AuthSession(sessionId, user.getId(),
                new Date(System.currentTimeMillis() + JwtUtils.REFRESH_TOKEN_EXPIRATION)));
        return Map.of("accessToken", jwt.generateAccessToken(user, sessionId),
                "refreshToken", jwt.generateRefreshToken(user, sessionId));
    }

    public boolean isActive(String token) {
        return isActive(jwt.getSessionId(token), jwt.getUserIdFromJwtToken(token));
    }

    public boolean isActive(String sessionId, String userId) {
        return sessionId != null && userId != null
                && sessions.existsByIdAndUserIdAndRevokedFalseAndExpiresAtAfter(sessionId, userId, new Date());
    }

    public void revoke(String token) {
        String sessionId = jwt.getSessionId(token);
        sessions.revoke(sessionId, jwt.getUserIdFromJwtToken(token));
        events.publishEvent(new SessionRevokedEvent(sessionId));
    }
}
