package com.cloudmedia.api.messaging;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaJobPublisherTests {
    RabbitTemplate rabbit;
    MediaJobPublisher publisher;
    MediaJobMessage job;

    @BeforeEach void setup() {
        rabbit = mock(RabbitTemplate.class);
        publisher = new MediaJobPublisher(rabbit);
        job = new MediaJobMessage(1, UUID.randomUUID().toString());
    }

    @Test void requiresBrokerConfirmationAndUsesPersistentDelivery() {
        doAnswer(invocation -> {
            MessagePostProcessor processor = invocation.getArgument(3);
            Message outgoing = processor.postProcessMessage(new Message(new byte[0], new MessageProperties()));
            assertThat(outgoing.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(outgoing.getMessageProperties().getMessageId()).isEqualTo(job.jobId());
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        publisher.publish(job);
    }

    @Test void nackAndUnroutableMessagesFailSubmission() {
        for (boolean returned : new boolean[]{false, true}) {
            doAnswer(invocation -> {
                CorrelationData data = invocation.getArgument(4);
                if (returned) data.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "exchange", "key"));
                data.getFuture().complete(new CorrelationData.Confirm(returned, "Rejected"));
                return null;
            }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));
            assertThatThrownBy(() -> publisher.publish(job)).isInstanceOf(JobSubmissionException.class);
        }
    }

    @Test void missingConfirmationTimesOut() {
        ReflectionTestUtils.setField(publisher, "confirmTimeout", 10L);
        assertThatThrownBy(() -> publisher.publish(job)).isInstanceOf(JobSubmissionException.class);
    }
}
