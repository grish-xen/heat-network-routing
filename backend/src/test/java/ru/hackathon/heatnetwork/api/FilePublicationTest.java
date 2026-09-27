package ru.hackathon.heatnetwork.api;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FilePublicationTest {
    @TempDir Path directory;

    @Test void transientDenialPreservesPreviousStatusUntilReplacementSucceeds() throws Exception {
        Path source = Files.writeString(directory.resolve("new.tmp"), "new status");
        Path target = Files.writeString(directory.resolve("status.json"), "old status");
        AtomicInteger calls = new AtomicInteger();
        List<Long> waits = new ArrayList<>();
        FilePublication.publish(source, target, (from, to, options) -> {
            if (calls.incrementAndGet() <= 2) throw new AccessDeniedException(to.toString());
            Files.move(from, to, options);
        }, millis -> {
            waits.add(millis);
            try { assertEquals("old status", Files.readString(target)); }
            catch (IOException failure) { throw new AssertionError(failure); }
        });
        assertEquals("new status", Files.readString(target));
        assertFalse(Files.exists(source));
        assertEquals(List.of(20L, 40L), waits);
    }

    @Test void persistentDenialStopsAndPreservesBothFiles() throws Exception {
        Path source = Files.writeString(directory.resolve("new.tmp"), "new");
        Path target = Files.writeString(directory.resolve("status.json"), "old");
        AtomicInteger calls = new AtomicInteger();
        List<Long> waits = new ArrayList<>();
        assertThrows(AccessDeniedException.class, () -> FilePublication.publish(source, target, (a, b, options) -> {
            calls.incrementAndGet();
            throw new AccessDeniedException(b.toString());
        }, waits::add));
        assertEquals(6, calls.get());
        assertEquals(620L, waits.stream().mapToLong(Long::longValue).sum());
        assertEquals("old", Files.readString(target));
        assertEquals("new", Files.readString(source));
    }

    @Test void fallbackAlsoRetriesSharingViolation() throws Exception {
        Path source = Files.writeString(directory.resolve("new.tmp"), "new");
        Path target = directory.resolve("result.json");
        AtomicInteger fallbackCalls = new AtomicInteger();
        FilePublication.publish(source, target, (a, b, options) -> {
            if (List.of(options).contains(StandardCopyOption.ATOMIC_MOVE))
                throw new AtomicMoveNotSupportedException(a.toString(), b.toString(), "test provider");
            if (fallbackCalls.incrementAndGet() == 1) throw new AccessDeniedException(b.toString());
            Files.move(a, b, options);
        }, millis -> { });
        assertEquals("new", Files.readString(target));
        assertEquals(2, fallbackCalls.get());
    }

    @Test void unrelatedIoErrorsAreNotRetried() {
        IOException original = new IOException("disk failure");
        IOException actual = assertThrows(IOException.class, () -> FilePublication.publish(directory.resolve("a"), directory.resolve("b"),
                (a, b, options) -> { throw original; }, millis -> fail("must not retry")));
        assertSame(original, actual);
    }

    @Test void interruptionStopsRetriesAndPreservesInterruptFlag() {
        try {
            assertThrows(InterruptedIOException.class, () -> FilePublication.publish(directory.resolve("a"), directory.resolve("b"),
                    (a, b, options) -> { throw new AccessDeniedException(b.toString()); },
                    millis -> { throw new InterruptedException(); }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
