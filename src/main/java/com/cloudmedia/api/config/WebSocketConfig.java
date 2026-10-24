package com.cloudmedia.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final ProgressChannelInterceptor progressChannelInterceptor;
    private final AuthenticatedWebSocketSessions sessions;

    public WebSocketConfig(ProgressChannelInterceptor progressChannelInterceptor, AuthenticatedWebSocketSessions sessions) {
        this.progressChannelInterceptor = progressChannelInterceptor;
        this.sessions = sessions;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Frontend sẽ kết nối vào đường dẫn này để mở ống nước (kết nối mạng)
        registry.addEndpoint("/ws")
                .withSockJS(); // Fallback an toàn nếu trình duyệt cũ không hỗ trợ chuẩn WebSocket
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Broker định tuyến thông báo đến từng phiên của người dùng.
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix("/user");
        // Các tin nhắn từ Client gửi lên (nếu có) sẽ mang tiền tố "/app"
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(progressChannelInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessions);
    }
}
