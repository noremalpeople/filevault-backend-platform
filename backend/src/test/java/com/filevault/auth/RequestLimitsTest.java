package com.filevault.auth;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestLimitsTest {
    @Test
    void windowResetsAndScopesAndSubjectsAreIsolated() {
        var clock = new AtomicLong();
        var limits = new RequestLimits(Duration.ofSeconds(2), 32, clock::get);
        assertEquals(0, limits.retryAfter("login", "a", 1));
        assertEquals(2, limits.retryAfter("login", "a", 1));
        assertEquals(0, limits.retryAfter("login", "b", 1));
        assertEquals(0, limits.retryAfter("share", "a", 1));
        clock.set(Duration.ofMillis(1500).toNanos());
        assertEquals(1, limits.retryAfter("login", "a", 1));
        clock.set(Duration.ofSeconds(2).toNanos());
        assertEquals(0, limits.retryAfter("login", "a", 1));
    }

    @Test
    void admitsExactlyTheConfiguredCountUnderContention() throws Exception {
        var limits = new RequestLimits(Duration.ofMinutes(1), 32, () -> 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = IntStream.range(0, 40).mapToObj(i -> executor.submit(
                    () -> limits.retryAfter("share", "same", 8))).toList();
            int admitted = 0;
            for (var call : calls) if (call.get() == 0) admitted++;
            assertEquals(8, admitted);
        }
    }
}
