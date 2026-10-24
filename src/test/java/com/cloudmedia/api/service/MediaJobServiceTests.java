package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.MediaJob;
import com.cloudmedia.api.messaging.*;
import com.cloudmedia.api.repository.MediaJobRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaJobServiceTests {
    MediaJobRepository repository;
    MediaJobPublisher publisher;
    MediaJobService jobs;

    @BeforeEach void setup() {
        repository = mock(MediaJobRepository.class);
        publisher = mock(MediaJobPublisher.class);
        jobs = new MediaJobService(repository, publisher);
    }

    @Test void persistsOwnerAndInputsBeforePublishingOnlyTheJobId() {
        String id = jobs.enqueueConversion("owner", "source");
        ArgumentCaptor<MediaJob> saved = ArgumentCaptor.forClass(MediaJob.class);
        var order = inOrder(repository, publisher);
        order.verify(repository).saveAndFlush(saved.capture());
        order.verify(publisher).publish(new MediaJobMessage(1, id));
        assertThat(saved.getValue().getUserId()).isEqualTo("owner");
        assertThat(saved.getValue().getFileId()).isEqualTo("source");
        assertThat(saved.getValue().getType()).isEqualTo(MediaJob.Type.CONVERT_MP3);
    }

    @Test void failedSubmissionDoesNotOverwriteAJobThatAlreadyStarted() {
        doAnswer(invocation -> {
            MediaJobMessage message = invocation.getArgument(0);
            throw new JobSubmissionException(message.jobId(), new IllegalStateException("Broker unavailable"));
        }).when(publisher).publish(any());
        assertThatThrownBy(() -> jobs.enqueueYouTube("owner", "https://youtu.be/example", null))
                .isInstanceOf(JobSubmissionException.class);
        verify(repository).transition(anyString(), eq(MediaJob.Status.PUBLISH_FAILED),
                eq(List.of(MediaJob.Status.QUEUED)), any(Instant.class));
    }

    @Test void otherUsersCannotReadOrCancelJobAndCompletedJobsCannotBeCancelled() {
        MediaJob job = new MediaJob(UUID.randomUUID().toString(), "owner", MediaJob.Type.YOUTUBE, null, "url", null);
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        assertThatThrownBy(() -> jobs.requireOwned(job.getId(), "other")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jobs.cancel(job.getId(), "other")).isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).transition(anyString(), any(), anyCollection(), any());
        org.springframework.test.util.ReflectionTestUtils.setField(job, "status", MediaJob.Status.COMPLETED);
        assertThatThrownBy(() -> jobs.cancel(job.getId(), "owner")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void completedAndCancelledStatesCannotBeOverwrittenByWorker() {
        jobs.completed("job");
        ArgumentCaptor<Collection<MediaJob.Status>> allowed = ArgumentCaptor.forClass(Collection.class);
        verify(repository).transition(eq("job"), eq(MediaJob.Status.COMPLETED), allowed.capture(), any());
        assertThat(allowed.getValue()).doesNotContain(MediaJob.Status.CANCELLED, MediaJob.Status.COMPLETED);
    }

    @Test void completionWinningTheCancellationRaceReturnsConflict() {
        MediaJob running = new MediaJob(UUID.randomUUID().toString(), "owner", MediaJob.Type.YOUTUBE, null, "url", null);
        MediaJob completed = new MediaJob(running.getId(), "owner", MediaJob.Type.YOUTUBE, null, "url", null);
        org.springframework.test.util.ReflectionTestUtils.setField(completed, "status", MediaJob.Status.COMPLETED);
        when(repository.findById(running.getId())).thenReturn(Optional.of(running), Optional.of(completed));
        assertThatThrownBy(() -> jobs.cancel(running.getId(), "owner"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }
}
