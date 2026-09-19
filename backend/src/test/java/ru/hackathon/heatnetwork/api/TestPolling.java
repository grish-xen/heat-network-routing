package ru.hackathon.heatnetwork.api;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

final class TestPolling {
    private TestPolling() { }
    static void eventually(Callable<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            do {
                if (condition.call()) return;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
        } catch (Exception exception) { throw new AssertionError(exception); }
        throw new AssertionError("Asynchronous operation did not complete in 10 seconds");
    }
}
