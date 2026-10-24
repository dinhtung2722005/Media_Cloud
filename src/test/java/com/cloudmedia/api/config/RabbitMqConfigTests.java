package com.cloudmedia.api.config;

import com.cloudmedia.api.messaging.MediaJobMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitMqConfigTests {
    RabbitMqConfig config = new RabbitMqConfig();

    @Test void declaresDurableQueueAndDeadLetterRouteWithEnoughAckTime() {
        var topology = config.mediaJobTopology(1800, 2).getDeclarables();
        var queue = topology.stream().filter(org.springframework.amqp.core.Queue.class::isInstance)
                .map(org.springframework.amqp.core.Queue.class::cast).filter(item -> item.getName().equals(RabbitMqConfig.QUEUE)).findFirst().orElseThrow();
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum");
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", RabbitMqConfig.DEAD_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitMqConfig.DEAD_ROUTING_KEY);
        assertThat(queue.getArguments().get("x-consumer-timeout")).isInstanceOf(Integer.class);
        assertThat((Integer) queue.getArguments().get("x-consumer-timeout")).isGreaterThan(3600000);
    }

    @Test void jsonConverterUsesFixedMessageTypeRatherThanJavaTypeHeaders() {
        var converter = config.mediaJobMessageConverter(new ObjectMapper());
        var job = new MediaJobMessage(1, UUID.randomUUID().toString());
        Message message = converter.toMessage(job, new MessageProperties());
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        message.getMessageProperties().setHeader("__TypeId__", "java.lang.Runtime");
        assertThat(converter.fromMessage(message)).isEqualTo(job);
    }

    @Test void retriesTwiceThenRejectsAndShutdownRequeuesWithoutRetry() {
        var factory = config.rabbitListenerContainerFactory(mock(SimpleRabbitListenerContainerFactoryConfigurer.class),
                mock(ConnectionFactory.class), config.mediaJobMessageConverter(new ObjectMapper()), 2);
        Advice[] advice = (Advice[]) ReflectionTestUtils.getField(factory, "adviceChain");
        AtomicInteger attempts = new AtomicInteger();
        DeliveryHandler listener = (channel, message) -> { attempts.incrementAndGet(); throw new IllegalStateException("Failed"); };
        ProxyFactory proxy = new ProxyFactory(listener);
        for (Advice item : advice) proxy.addAdvice(item);
        DeliveryHandler wrapped = (DeliveryHandler) proxy.getProxy();
        assertThatThrownBy(() -> wrapped.deliver(null, new Message(new byte[0]))).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(attempts).hasValue(2);

        attempts.set(0);
        DeliveryHandler stopping = (channel, message) -> { attempts.incrementAndGet(); throw new ImmediateRequeueAmqpException("Shutdown"); };
        ProxyFactory stoppedProxy = new ProxyFactory(stopping);
        for (Advice item : advice) stoppedProxy.addAdvice(item);
        assertThatThrownBy(() -> ((DeliveryHandler) stoppedProxy.getProxy()).deliver(null, new Message(new byte[0])))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
        assertThat(attempts).hasValue(1);
    }

    // Listener-container advice receives channel first and the raw AMQP message second.
    public interface DeliveryHandler { void deliver(Object channel, Message message); }
}
