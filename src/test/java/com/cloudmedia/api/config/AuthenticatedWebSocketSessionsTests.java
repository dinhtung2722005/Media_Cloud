package com.cloudmedia.api.config;

import com.cloudmedia.api.security.SessionRevokedEvent;
import com.cloudmedia.api.service.AuthSessionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.*;
import static org.mockito.Mockito.*;

class AuthenticatedWebSocketSessionsTests {
    @Test void logoutClosesOnlySocketsForTheRevokedSession() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        when(sessions.isActive(anyString(), eq("owner"))).thenReturn(true);
        AuthenticatedWebSocketSessions registry = new AuthenticatedWebSocketSessions(sessions);
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketHandler handler = registry.decorate(delegate);
        WebSocketSession first = socket("first", "session-a");
        WebSocketSession second = socket("second", "session-b");
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);
        registry.onSessionRevoked(new SessionRevokedEvent("session-a"));
        verify(first).close(CloseStatus.POLICY_VIOLATION);
        verify(second, never()).close(any());
        verify(delegate).afterConnectionEstablished(first);
        verify(delegate).afterConnectionEstablished(second);
    }

    @Test void revokedSessionCannotFinishHandshake() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketSession socket = socket("first", "revoked");
        new AuthenticatedWebSocketSessions(sessions).decorate(delegate).afterConnectionEstablished(socket);
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(delegate);
    }

    private WebSocketSession socket(String id, String sessionId) {
        WebSocketSession socket = mock(WebSocketSession.class);
        var authentication = new UsernamePasswordAuthenticationToken("owner", null, List.of());
        authentication.setDetails(sessionId);
        when(socket.getId()).thenReturn(id);
        when(socket.getPrincipal()).thenReturn(authentication);
        return socket;
    }
}
