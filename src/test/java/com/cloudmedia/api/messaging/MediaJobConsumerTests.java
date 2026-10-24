package com.cloudmedia.api.messaging;

import com.cloudmedia.api.entity.MediaJob;
import com.cloudmedia.api.service.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.amqp.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaJobConsumerTests {
    MediaJobService jobs;
    MediaEngineService engine;
    MediaJobConsumer consumer;
    String id = UUID.randomUUID().toString();

    @BeforeEach void setup() {
        jobs = mock(MediaJobService.class);
        engine = mock(MediaEngineService.class);
        consumer = new MediaJobConsumer(jobs, engine);
        when(jobs.start(id)).thenReturn(true);
    }

    @Test void dispatchesConversionAndAcknowledgesOnlyAfterCompletion() {
        MediaJob job = new MediaJob(id, "owner", MediaJob.Type.CONVERT_MP3, "source", null, null);
        when(jobs.find(id)).thenReturn(job);
        consumer.consume(new MediaJobMessage(1, id));
        var order = inOrder(jobs, engine);
        order.verify(jobs).start(id);
        order.verify(engine).convertToMp3(eq("source"), eq("owner"), eq(id), any(BooleanSupplier.class));
        order.verify(jobs).completed(id);
    }

    @Test void failedDeliveryIsThrownForContainerRetry() {
        MediaJob job = new MediaJob(id, "owner", MediaJob.Type.YOUTUBE, null, "https://youtu.be/example", null);
        when(jobs.find(id)).thenReturn(job);
        doThrow(new IllegalStateException("Failed")).when(engine).downloadYouTubeVideo(anyString(), anyString(), isNull(), anyString(), any());
        assertThatThrownBy(() -> consumer.consume(new MediaJobMessage(1, id))).isInstanceOf(IllegalStateException.class);
        verify(jobs).failed(id);
        verify(jobs, never()).completed(id);
    }

    @Test void completedOrCancelledJobDoesNotRunAgain() {
        when(jobs.find(id)).thenReturn(new MediaJob(id, "owner", MediaJob.Type.YOUTUBE, null, "url", null));
        when(jobs.start(id)).thenReturn(false);
        consumer.consume(new MediaJobMessage(1, id));
        verifyNoInteractions(engine);
    }

    @Test void cancellationAcknowledgesWithoutRetryAndShutdownRequestsRequeue() {
        when(jobs.find(id)).thenReturn(new MediaJob(id, "owner", MediaJob.Type.CONVERT_MP3, "source", null, null));
        doThrow(new CancellationException()).when(engine).convertToMp3(anyString(), anyString(), anyString(), any());
        when(jobs.isCancelled(id)).thenReturn(true);
        consumer.consume(new MediaJobMessage(1, id));
        verify(jobs, never()).failed(id);
        doAnswer(invocation -> { Thread.currentThread().interrupt(); throw new IllegalStateException("Shutdown"); })
                .when(engine).convertToMp3(anyString(), anyString(), anyString(), any());
        try {
            assertThatThrownBy(() -> consumer.consume(new MediaJobMessage(1, id))).isInstanceOf(ImmediateRequeueAmqpException.class);
            verify(jobs).requeue(id);
        } finally { Thread.interrupted(); }
    }

    @Test void malformedMessageIsRejectedWithoutExecutingTools() {
        assertThatThrownBy(() -> consumer.consume(new MediaJobMessage(99, id))).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> consumer.consume(new MediaJobMessage(1, "../path"))).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(jobs, engine);
    }
}
