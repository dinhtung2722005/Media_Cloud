package com.cloudmedia.api.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class ProgressChannelInterceptor implements ChannelInterceptor {
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.SUBSCRIBE || command == StompCommand.SEND) {
            if (!(accessor.getUser() instanceof Authentication authentication)
                    || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
                throw new AccessDeniedException("Vui lòng đăng nhập để nhận tiến độ xử lý");
            }
        }
        if (command == StompCommand.SUBSCRIBE && !"/user/queue/progress".equals(accessor.getDestination())) {
            throw new AccessDeniedException("Không có quyền đăng ký kênh này");
        }
        if (command == StompCommand.SEND) {
            throw new AccessDeniedException("Kênh tiến độ chỉ nhận thông báo từ máy chủ");
        }
        return message;
    }
}
