package com.cloudmedia.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ProgressChannelInterceptorTests {
    private final ProgressChannelInterceptor interceptor = new ProgressChannelInterceptor();

    @Test
    void allowsOnlyAuthenticatedPersonalProgressSubscriptions() {
        Message<?> subscription = message(StompCommand.SUBSCRIBE, "/user/queue/progress", true);
        assertThat(interceptor.preSend(subscription, null)).isSameAs(subscription);
        assertThatThrownBy(() -> interceptor.preSend(message(StompCommand.SUBSCRIBE, "/user/queue/progress", false), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> interceptor.preSend(message(StompCommand.CONNECT, null, false), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void blocksGlobalOtherUserAndRawBrokerDestinations() {
        for (String destination : List.of("/topic/progress/global", "/user/bob/queue/progress", "/queue/progress-user-bob")) {
            assertThatThrownBy(() -> interceptor.preSend(message(StompCommand.SUBSCRIBE, destination, true), null))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void clientsCannotPublishFakeProgress() {
        assertThatThrownBy(() -> interceptor.preSend(message(StompCommand.SEND, "/queue/progress", true), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    private Message<?> message(StompCommand command, String destination, boolean authenticated) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) accessor.setDestination(destination);
        if (authenticated) accessor.setUser(new UsernamePasswordAuthenticationToken("alice-id", null, List.of()));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
