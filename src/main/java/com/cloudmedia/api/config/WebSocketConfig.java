package com.cloudmedia.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Frontend sẽ kết nối vào đường dẫn này để mở ống nước (kết nối mạng)
        registry.addEndpoint("/ws/media")
                .setAllowedOriginPatterns("*") // Cho phép mọi domain kết nối (sau này có thể chặn lại cho an toàn)
                .withSockJS(); // Fallback an toàn nếu trình duyệt cũ không hỗ trợ chuẩn WebSocket
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Kích hoạt một trạm phát sóng có tên bắt đầu bằng "/topic"
        registry.enableSimpleBroker("/topic");
        // Các tin nhắn từ Client gửi lên (nếu có) sẽ mang tiền tố "/app"
        registry.setApplicationDestinationPrefixes("/app");
    }
}