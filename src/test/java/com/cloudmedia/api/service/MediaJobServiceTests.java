package com.cloudmedia.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmedia.api.domain.DomainException;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.messaging.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MediaJobServiceTests extends BusinessTestBase {
  String enqueue() {
    return jobs.enqueueYouTube(user, "https://youtu.be/example", null);
  }

  @Test
  void jobAndOutboxCommitTogetherAndRequestKeysAreScopedToPayload() {
    String id = jobs.enqueueYouTube(user, "https://youtu.be/example", null, "request1");
    assertThat(outbox.existsById(id)).isTrue();
    assertThat(jobs.find(id).getStatus()).isEqualTo(MediaJob.Status.PENDING_DISPATCH);
    assertThat(jobs.enqueueYouTube(user, "https://youtu.be/example", null, "request1"))
        .isEqualTo(id);
    assertThatThrownBy(() -> jobs.enqueueYouTube(user, "https://youtu.be/other", null, "request1"))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () ->
                accounts.locked(
                    user,
                    () -> {
                      jobs.enqueueYouTube(user, "https://youtu.be/rollback", null, "rollback");
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jobRows.findByUserIdAndRequestKey(user, "rollback")).isEmpty();
    assertThat(outbox.count()).isEqualTo(1);
    assertThat(
            events.stream(JobEvents.Notice.class)
                .filter(e -> e.payload().get("status").equals("PENDING_DISPATCH"))
                .count())
        .isEqualTo(1);
  }

  @Test
  void twoIndependentWorkersOnlyObtainOneLease() throws Exception {
    String id = enqueue();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Callable<Boolean> attempt =
          () -> {
            start.await();
            return jobs.claim(id).isPresent();
          };
      var a = pool.submit(attempt);
      var b = pool.submit(attempt);
      start.countDown();
      assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      pool.shutdownNow();
    }
    assertThat(jobs.find(id).getAttempts()).isEqualTo(1);
  }

  @Test
  void cancelWinningFinalizationDoesNotPublishAFile() throws Exception {
    var execution = jobs.claim(enqueue()).orElseThrow();
    MediaFile result = result(execution);
    jobs.cancel(execution.job().getId(), user);
    assertThat(jobs.find(result.getId()).getStatus()).isEqualTo(MediaJob.Status.CANCEL_REQUESTED);
    assertThat(jobs.finish(execution, result)).isFalse();
    assertThat(files.existsById(result.getId())).isFalse();
    assertThat(jobs.find(result.getId()).getStatus()).isEqualTo(MediaJob.Status.CANCELLED);
    assertThat(jobs.find(result.getId()).getReservedBytes()).isZero();
    storage.cleanup(storage.resolve(result.getStoragePath()));
  }

  @Test
  void completionWinningCancellationReturns409AndRedeliveryIsSkipped() throws Exception {
    var execution = jobs.claim(enqueue()).orElseThrow();
    MediaFile result = result(execution);
    assertThat(jobs.finish(execution, result)).isTrue();
    assertThatThrownBy(() -> jobs.cancel(result.getId(), user))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.status().value()).isEqualTo(409));
    assertThat(jobs.claim(result.getId())).isEmpty();
    assertThat(files.findById(result.getId())).isPresent();
  }

  @Test
  void expiredWorkerCannotCommitOrDeleteNewAttempt() throws Exception {
    var old = jobs.claim(enqueue()).orElseThrow();
    MediaFile stale = result(old);
    accounts.locked(
        user,
        () -> {
          MediaJob job = jobRows.lockById(old.job().getId()).orElseThrow();
          ReflectionTestUtils.setField(job, "leaseUntil", Instant.now().minusSeconds(1));
          jobRows.saveAndFlush(job);
        });
    jobs.recover(old.job().getId());
    var current = jobs.claim(old.job().getId()).orElseThrow();
    MediaFile winner = result(current);
    assertThat(jobs.finish(old, stale)).isFalse();
    assertThat(jobs.finish(current, winner)).isTrue();
    storage.cleanup(storage.resolve(stale.getStoragePath()));
    assertThat(storage.resolve(winner.getStoragePath())).exists();
    assertThat(jobs.find(winner.getId()).getAttempts()).isEqualTo(2);
  }

  @Test
  void processingFailureIsRetryingBeforeTerminalFailure() {
    var first = jobs.claim(enqueue()).orElseThrow();
    jobs.failed(first, new IllegalStateException("private tool log"));
    MediaJob retry = jobs.find(first.job().getId());
    assertThat(retry.getStatus()).isEqualTo(MediaJob.Status.RETRYING);
    assertThat(retry.getErrorMessage()).doesNotContain("private tool log");
    accounts.locked(
        user,
        () -> {
          var j = jobRows.lockById(retry.getId()).orElseThrow();
          ReflectionTestUtils.setField(j, "nextAttemptAt", Instant.now().minusSeconds(1));
        });
    var second = jobs.claim(retry.getId()).orElseThrow();
    jobs.failed(second, new IllegalStateException());
    assertThat(jobs.find(retry.getId()).getStatus()).isEqualTo(MediaJob.Status.FAILED);
    assertThat(jobs.find(retry.getId()).getReservedBytes()).isZero();
  }

  @Test
  void lostPublisherConfirmationCanRedeliverWithoutLosingJob() {
    String id = enqueue();
    MediaJobPublisher publisher = mock(MediaJobPublisher.class);
    doAnswer(
            call -> {
              assertThat(jobs.claim(id)).isPresent();
              throw new JobSubmissionException(id, new IllegalStateException("lost confirm"));
            })
        .when(publisher)
        .publish(any());
    new JobOutboxDispatcher(outbox, jobRows, publisher, jobs).dispatch();
    assertThat(jobs.find(id).getStatus()).isEqualTo(MediaJob.Status.RUNNING);
    assertThat(jobs.claim(id)).isEmpty();
  }

  @Test
  void accountLimitsReserveSpaceAndAllowOtherAccounts() {
    String first = enqueue(), second = enqueue();
    assertThat(jobs.claim(first)).isPresent();
    assertThat(jobs.claim(second)).isEmpty();
    enqueue();
    assertThatThrownBy(this::enqueue)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.status().value()).isEqualTo(429));
    jobs.cancel(second, user);
    assertThat(enqueue()).isNotEmpty();
    String another = UUID.randomUUID().toString();
    users.saveAndFlush(new User(another, another, null, "h", java.time.LocalDateTime.now()));
    assertThat(jobs.enqueueYouTube(another, "url", null)).isNotEmpty();
  }

  @Test
  void quotaAndOwnershipFailuresDoNotCreateOutbox() {
    files.saveAndFlush(
        new MediaFile(
            UUID.randomUUID().toString(),
            "full.txt",
            "dummy",
            "txt",
            21474836480L - 512,
            null,
            user,
            "Ready",
            "Local"));
    assertThatThrownBy(this::enqueue)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.status().value()).isEqualTo(413));
    assertThat(
            jobRows.findByUserIdOrderByCreatedAtDesc(
                user, org.springframework.data.domain.PageRequest.of(0, 10)))
        .isEmpty();
    assertThatThrownBy(() -> jobs.enqueueConversion(user, "foreign"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void ambiguousCommitCleanupPreservesPublishedResult() throws Exception {
    var execution = jobs.claim(enqueue()).orElseThrow();
    MediaFile result = result(execution);
    assertThat(jobs.finish(execution, result)).isTrue();
    var prepared =
        new MediaEngineService.Prepared(result, storage.resolve(result.getStoragePath()), storage);
    jobs.discard(execution, prepared);
    assertThat(prepared.output()).exists();
  }

  @Test
  void finalizationRollbackKeepsRunningStateAndDoesNotAnnounceCompletion() throws Exception {
    var execution = jobs.claim(enqueue()).orElseThrow();
    MediaFile result = result(execution);
    result.setTitle("x".repeat(256));
    assertThatThrownBy(() -> jobs.finish(execution, result))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(files.existsById(result.getId())).isFalse();
    assertThat(jobs.find(result.getId()).getStatus()).isEqualTo(MediaJob.Status.RUNNING);
    assertThat(
            events.stream(JobEvents.Notice.class)
                .filter(e -> e.payload().get("status").equals("COMPLETED")))
        .isEmpty();
    result.setTitle("valid.mp4");
    assertThat(jobs.finish(execution, result)).isTrue();
    assertThat(
            events.stream(JobEvents.Notice.class)
                .filter(e -> e.payload().get("status").equals("COMPLETED")))
        .hasSize(1);
  }
}
