package com.cloudmedia.api.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class MediaProcessRunnerTests {
    @Test void drainsOutputFromRealProcess() throws Exception {
        List<String> output = new CopyOnWriteArrayList<>();
        int exit = new MediaProcessRunner().run(command("output"), Duration.ofSeconds(10), output::add);
        assertThat(exit).isZero();
        assertThat(output).contains("ready");
    }

    @Test void timeoutKillsProcessEvenWhenReaderIsWaiting() throws Exception {
        MediaProcessRunner runner = spy(new MediaProcessRunner());
        AtomicReference<Process> process = new AtomicReference<>();
        doAnswer(invocation -> {
            Process started = (Process) invocation.callRealMethod();
            process.set(started);
            return started;
        }).when(runner).startProcess(anyList());
        assertThatThrownBy(() -> runner.run(command("sleep"), Duration.ofMillis(200), line -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("thời gian");
        assertThat(process.get().waitFor(3, TimeUnit.SECONDS)).isTrue();
        assertThat(process.get().isAlive()).isFalse();
    }

    @Test void interruptionKillsProcessAndPreservesInterruptFlag() throws Exception {
        MediaProcessRunner runner = spy(new MediaProcessRunner());
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Process> process = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        doAnswer(invocation -> {
            Process child = (Process) invocation.callRealMethod();
            process.set(child);
            started.countDown();
            return child;
        }).when(runner).startProcess(anyList());
        Thread worker = new Thread(() -> {
            try { runner.run(command("sleep"), Duration.ofMinutes(1), line -> {}); }
            catch (Throwable exception) {
                failure.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
            assertThat(interrupted).isTrue();
            assertThat(process.get().waitFor(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            worker.interrupt();
            if (process.get() != null) process.get().destroyForcibly();
        }
    }

    @Test void cancellationFlagStopsProcessWithoutInterruptingConsumerThread() throws Exception {
        MediaProcessRunner runner = spy(new MediaProcessRunner());
        AtomicReference<Process> process = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        doAnswer(invocation -> {
            Process child = (Process) invocation.callRealMethod();
            process.set(child);
            cancelled.set(true);
            return child;
        }).when(runner).startProcess(anyList());
        assertThatThrownBy(() -> runner.run(command("sleep"), Duration.ofMinutes(1), line -> {}, cancelled::get))
                .isInstanceOf(CancellationException.class);
        assertThat(process.get().waitFor(3, TimeUnit.SECONDS)).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private List<String> command(String mode) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), ProcessFixture.class.getName(), mode);
    }
}
