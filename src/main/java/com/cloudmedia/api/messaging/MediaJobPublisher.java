package com.cloudmedia.api.messaging;

import com.cloudmedia.api.config.RabbitMqConfig;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MediaJobPublisher {
    private final RabbitTemplate rabbit;
    @Value("${app.rabbit.publish-confirm-timeout-ms:5000}") private long confirmTimeout = 5000;

    public MediaJobPublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    public void publish(MediaJobMessage message) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.convertAndSend(RabbitMqConfig.EXCHANGE, RabbitMqConfig.ROUTING_KEY, message, outgoing -> {
                outgoing.getMessageProperties().setMessageId(message.jobId());
                outgoing.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return outgoing;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout, TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("Broker không nhận được tác vụ");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JobSubmissionException(message.jobId(), exception);
        } catch (Exception exception) {
            throw new JobSubmissionException(message.jobId(), exception);
        }
    }
}
