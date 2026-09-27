package ru.hackathon.heatnetwork.api;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;

/** Bounded retries for transient Windows sharing violations, without deleting the old file. */
final class FilePublication {
    private FilePublication() { }

    @FunctionalInterface interface Move {
        void run(Path source, Path target, CopyOption... options) throws IOException;
    }
    @FunctionalInterface interface Pause { void run(long millis) throws InterruptedException; }

    static void publish(Path source, Path target) throws IOException {
        publish(source, target, Files::move, Thread::sleep);
    }

    static void publish(Path source, Path target, Move move, Pause pause) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try { move.run(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) {
                    move.run(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException denied) {
                if (attempt == 5) throw denied;
                try { pause.run(20L << attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException failure = new InterruptedIOException("File publication interrupted");
                    failure.initCause(interrupted);
                    failure.addSuppressed(denied);
                    throw failure;
                }
            }
        }
    }
}
