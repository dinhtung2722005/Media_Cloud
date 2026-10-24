package com.cloudmedia.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cloudmedia.api.messaging.MediaJobMessage;
import org.springframework.amqp.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.*;
import org.springframework.retry.policy.SimpleRetryPolicy;
import java.util.Map;

@Configuration
public class RabbitMqConfig {
    public static final String EXCHANGE = "media.jobs.exchange";
    public static final String QUEUE = "media.jobs";
    public static final String ROUTING_KEY = "media.process";
    public static final String DEAD_EXCHANGE = "media.jobs.dlx";
    public static final String DEAD_QUEUE = "media.jobs.failed";
    public static final String DEAD_ROUTING_KEY = "media.failed";

    @Bean
    public Declarables mediaJobTopology(@Value("${app.jobs.timeout-seconds:1800}") long timeoutSeconds,
                                       @Value("${app.rabbit.retry-max-attempts:2}") int attempts) {
        if (timeoutSeconds < 1 || attempts < 1 || attempts > 10) throw new IllegalArgumentException("Giới hạn retry/timeout không hợp lệ");
        // Encode the acknowledgment deadline as an AMQP integer.
        int ackTimeout = Math.toIntExact(Math.addExact(Math.multiplyExact(
                Math.addExact(Math.multiplyExact(timeoutSeconds, 1000L), 5000L), attempts), 120000L));
        DirectExchange jobs = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dead = new DirectExchange(DEAD_EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).quorum().deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY).withArgument("x-consumer-timeout", ackTimeout).build();
        Queue failed = QueueBuilder.durable(DEAD_QUEUE).quorum().build();
        return new Declarables(jobs, dead, queue, failed,
                BindingBuilder.bind(queue).to(jobs).with(ROUTING_KEY),
                BindingBuilder.bind(failed).to(dead).with(DEAD_ROUTING_KEY));
    }

    @Bean
    public Jackson2JsonMessageConverter mediaJobMessageConverter(ObjectMapper mapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(mapper, "com.cloudmedia.api.messaging");
        converter.setClassMapper(new ClassMapper() {
            public void fromClass(Class<?> type, MessageProperties properties) {
                properties.setHeader("media-schema", "media-job-v1");
            }
            public Class<?> toClass(MessageProperties properties) { return MediaJobMessage.class; }
        });
        return converter;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connection,
            Jackson2JsonMessageConverter converter, @Value("${app.rabbit.retry-max-attempts:2}") int attempts) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connection);
        factory.setMessageConverter(converter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        SimpleRetryPolicy retry = new SimpleRetryPolicy(attempts, Map.of(
                ImmediateRequeueAmqpException.class, false, AmqpRejectAndDontRequeueException.class, false), true, true);
        MessageRecoverer recoverer = (message, cause) -> {
            for (Throwable current = cause; current != null; current = current.getCause()) {
                if (current instanceof ImmediateRequeueAmqpException requeue) throw requeue;
            }
            throw new AmqpRejectAndDontRequeueException("Tác vụ thất bại sau giới hạn retry", cause);
        };
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().retryPolicy(retry)
                .backOffOptions(1000, 2, 5000).recoverer(recoverer).build());
        return factory;
    }
}
