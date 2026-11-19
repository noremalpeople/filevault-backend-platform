package com.filevault.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.LongSupplier;

final class RequestLimits {
    private record Key(String scope, String subject) { }
    private record Window(long start, long count) { }

    private final Cache<Key, Window> windows;
    private final LongSupplier clock;
    private final long windowNanos;

    RequestLimits(Duration duration, long capacity, LongSupplier clock) {
        if (duration.isNegative() || duration.isZero() || capacity < 1) throw new IllegalArgumentException("Invalid rate limit settings.");
        this.clock = clock;
        windowNanos = duration.toNanos();
        windows = Caffeine.newBuilder().maximumSize(capacity).expireAfterAccess(duration.multipliedBy(2)).build();
    }

    long retryAfter(String scope, String subject, int limit) {
        var key = new Key(scope, subject);
        long now = clock.getAsLong();
        var window = windows.asMap().compute(key, (ignored, current) -> {
            if (current == null || now - current.start() >= windowNanos) return new Window(now, 1);
            if (current.count() > limit) return current;
            return new Window(current.start(), current.count() + 1);
        });
        if (window.count() <= limit) return 0;
        long nanos = Math.max(1, windowNanos - Math.max(0, now - window.start()));
        return (nanos + 999_999_999) / 1_000_000_000;
    }
}
