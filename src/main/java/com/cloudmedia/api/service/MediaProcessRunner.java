package com.cloudmedia.api.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class MediaProcessRunner {
    public int run(List<String> command, Duration timeout, Consumer<String> output)
            throws IOException, InterruptedException {
        return run(command, timeout, output, () -> false);
    }

    public int run(List<String> command, Duration timeout, Consumer<String> output, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Timeout phải lớn hơn 0");
        long timeoutNanos = timeout.toNanos();
        if (cancelled.getAsBoolean()) throw new CancellationException("Đã hủy tác vụ");
        Process process = startProcess(command);
        ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "media-process-output");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> reading = reader.submit(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) output.accept(line);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (true) {
                if (cancelled.getAsBoolean()) throw new CancellationException("Đã hủy tác vụ");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Tác vụ vượt quá thời gian cho phép");
                long waitMillis = Math.max(1, Math.min(500, TimeUnit.NANOSECONDS.toMillis(remaining)));
                if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) break;
            }
            try {
                reading.get(2, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                throw new IOException("Không thể đọc kết quả tiến trình", exception.getCause());
            } catch (TimeoutException exception) {
                throw new IOException("Tiến trình không đóng luồng dữ liệu", exception);
            }
            return process.exitValue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            // Stop the tool tree after timeout, cancellation, or worker interruption.
            if (process.isAlive()) {
                try { process.toHandle().descendants().forEach(child -> child.destroyForcibly()); }
                catch (UnsupportedOperationException ignored) {}
                process.destroyForcibly();
            }
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            reading.cancel(true);
            reader.shutdownNow();
        }
    }

    Process startProcess(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PYTHONUTF8", "1");
        return builder.start();
    }
}
