package com.cloudmedia.api.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

class MediaJobConsumerTests {
  MediaJobService jobs;
  MediaEngineService engine;
  MediaJobConsumer consumer;
  String id = UUID.randomUUID().toString();
  MediaJobService.Execution execution;

  @BeforeEach
  void setup() {
    jobs = mock(MediaJobService.class);
    engine = mock(MediaEngineService.class);
    consumer = new MediaJobConsumer(jobs, engine);
    execution =
        new MediaJobService.Execution(
            new MediaJob(id, "owner", MediaJob.Type.YOUTUBE, null, "url", null),
            UUID.randomUUID().toString());
    when(jobs.claim(id)).thenReturn(Optional.of(execution));
    when(jobs.cancellation(execution)).thenReturn(() -> false);
  }

  @Test
  void acknowledgesOnlyAfterTransactionalFinalization() {
    var result = mock(MediaEngineService.Prepared.class);
    when(engine.prepare(any(), anyString(), any(), any())).thenReturn(result);
    when(jobs.finish(eq(execution), any())).thenReturn(true);
    consumer.consume(new MediaJobMessage(1, id));
    var order = inOrder(engine, jobs);
    order.verify(jobs).claim(id);
    order.verify(jobs).cancellation(execution);
    order.verify(engine).prepare(any(), anyString(), any(), any());
    order.verify(jobs).finish(eq(execution), any());
    verify(result, never()).close();
  }

  @Test
  void losingCancellationChecksCommittedMetadataBeforeDiscardingOutput() {
    var result = mock(MediaEngineService.Prepared.class);
    when(engine.prepare(any(), anyString(), any(), any())).thenReturn(result);
    consumer.consume(new MediaJobMessage(1, id));
    verify(jobs).discard(execution, result);
  }

  @Test
  void durableRetryHandlesToolFailureAndDatabaseFailurePropagates() {
    var error = new IllegalStateException("tool");
    when(engine.prepare(any(), anyString(), any(), any())).thenThrow(error);
    consumer.consume(new MediaJobMessage(1, id));
    verify(jobs).failed(execution, error);
    doThrow(new IllegalStateException("database")).when(jobs).failed(execution, error);
    assertThatThrownBy(() -> consumer.consume(new MediaJobMessage(1, id))).hasMessage("database");
  }

  @Test
  void duplicatesDoNotRunTools() {
    when(jobs.claim(id)).thenReturn(Optional.empty());
    consumer.consume(new MediaJobMessage(1, id));
    verifyNoInteractions(engine);
  }

  @Test
  void malformedMessagesAreRejected() {
    assertThatThrownBy(() -> consumer.consume(new MediaJobMessage(99, id)))
        .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    assertThatThrownBy(() -> consumer.consume(new MediaJobMessage(1, "../path")))
        .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    verifyNoInteractions(jobs, engine);
  }
}
