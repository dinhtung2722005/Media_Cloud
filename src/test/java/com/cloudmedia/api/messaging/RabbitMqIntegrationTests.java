package com.cloudmedia.api.messaging;

import com.cloudmedia.api.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "RUN_RABBITMQ_IT", matches = "true")
class RabbitMqIntegrationTests {
    CachingConnectionFactory connection;
    RabbitAdmin admin;
    RabbitTemplate rabbit;
    RabbitMqConfig config = new RabbitMqConfig();
    SimpleMessageListenerContainer container;

    @BeforeEach void setup() {
        String vhost = System.getenv("RABBITMQ_VHOST");
        if (vhost == null || !vhost.startsWith("media-integration-")) {
            throw new IllegalStateException("Integration tests require an isolated media-integration-* virtual host");
        }
        connection = new CachingConnectionFactory(System.getenv("RABBITMQ_HOST"), Integer.parseInt(System.getenv("RABBITMQ_PORT")));
        connection.setUsername(System.getenv("RABBITMQ_USERNAME"));
        connection.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        connection.setVirtualHost(vhost);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        admin = new RabbitAdmin(connection);
        for (Declarable item : config.mediaJobTopology(1800, 2).getDeclarables()) {
            if (item instanceof Exchange exchange) admin.declareExchange(exchange);
            if (item instanceof Queue queue) admin.declareQueue(queue);
            if (item instanceof Binding binding) admin.declareBinding(binding);
        }
        rabbit = new RabbitTemplate(connection);
        rabbit.setMandatory(true);
        rabbit.setMessageConverter(config.mediaJobMessageConverter(new ObjectMapper()));
    }

    @AfterEach void cleanup() {
        if (container != null) container.stop();
        if (admin != null) {
            admin.deleteQueue(RabbitMqConfig.QUEUE);
            admin.deleteQueue(RabbitMqConfig.DEAD_QUEUE);
            admin.deleteExchange(RabbitMqConfig.EXCHANGE);
            admin.deleteExchange(RabbitMqConfig.DEAD_EXCHANGE);
        }
        if (connection != null) connection.destroy();
    }

    @Test void confirmedPersistentJsonIsDeliveredAndAcknowledged() throws Exception {
        BlockingQueue<MediaJobMessage> received = new LinkedBlockingQueue<>();
        start(message -> received.add((MediaJobMessage) rabbit.getMessageConverter().fromMessage(message)));
        MediaJobMessage job = new MediaJobMessage(1, UUID.randomUUID().toString());
        new MediaJobPublisher(rabbit).publish(job);
        assertThat(received.poll(10, TimeUnit.SECONDS)).isEqualTo(job);
        awaitQueueCount(RabbitMqConfig.QUEUE, 0);
        assertThat(admin.getQueueProperties(RabbitMqConfig.DEAD_QUEUE).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).isEqualTo(0);
    }

    @Test void consumerFailureRetriesTwiceThenDeadLettersOriginalMessage() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        start(message -> { attempts.incrementAndGet(); throw new IllegalStateException("Test failure"); });
        MediaJobMessage job = new MediaJobMessage(1, UUID.randomUUID().toString());
        new MediaJobPublisher(rabbit).publish(job);
        Message failed = rabbit.receive(RabbitMqConfig.DEAD_QUEUE, 15000);
        assertThat(failed).isNotNull();
        assertThat(rabbit.getMessageConverter().fromMessage(failed)).isEqualTo(job);
        assertThat(failed.getMessageProperties().getHeaders()).containsKey("x-death");
        assertThat(attempts).hasValue(2);
    }

    private void start(MessageListener listener) {
        var configurer = mock(SimpleRabbitListenerContainerFactoryConfigurer.class);
        var factory = config.rabbitListenerContainerFactory(configurer, connection,
                config.mediaJobMessageConverter(new ObjectMapper()), 2);
        factory.setConnectionFactory(connection);
        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId("media-integration-" + UUID.randomUUID());
        endpoint.setQueueNames(RabbitMqConfig.QUEUE);
        endpoint.setMessageListener(listener);
        container = factory.createListenerContainer(endpoint);
        container.setShutdownTimeout(1000);
        container.start();
    }

    private void awaitQueueCount(String name, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (admin.getQueueProperties(name).get(RabbitAdmin.QUEUE_MESSAGE_COUNT).equals(expected)) return;
            Thread.sleep(50);
        }
        assertThat(admin.getQueueProperties(name).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).isEqualTo(expected);
    }
}
