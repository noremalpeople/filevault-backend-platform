package com.filevault.storage;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnWebApplication
class MaintenanceJobs {
    private static final Logger LOG = LoggerFactory.getLogger(MaintenanceJobs.class);
    private static final int MAX_ATTEMPTS = 3;
    private final Set<Long> submitted = ConcurrentHashMap.newKeySet();
    private final MaintenanceJobMapper jobs;
    private final StorageReconciliation scanner;
    private final ThreadPoolTaskExecutor executor;
    private final Duration grace;
    private final int lease;
    private final int retry;

    MaintenanceJobs(MaintenanceJobMapper jobs, StorageReconciliation scanner,
                    @Qualifier("storageMaintenanceExecutor") ThreadPoolTaskExecutor executor,
                    @Value("${filevault.reconcile-grace-seconds}") long grace,
                    @Value("${filevault.maintenance.lease-seconds}") int lease,
                    @Value("${filevault.maintenance.retry-seconds}") int retry) {
        if (grace < 1 || lease < 1 || retry < 1) throw new IllegalArgumentException("Invalid maintenance timing.");
        this.jobs = jobs;
        this.scanner = scanner;
        this.executor = executor;
        this.grace = Duration.ofSeconds(grace);
        this.lease = lease;
        this.retry = retry;
    }

    void enqueue(long period) {
        jobs.enqueue(period);
    }

    void dispatch() {
        jobs.due().forEach(this::submit);
    }

    void submit(long id) {
        if (!submitted.add(id)) return;
        try {
            executor.execute(() -> {
                try {
                    run(id);
                } catch (RuntimeException failure) {
                    LOG.warn("Storage audit job {} coordination failed with {}", id, failure.getClass().getSimpleName());
                } finally {
                    submitted.remove(id);
                }
            });
        } catch (RejectedExecutionException busy) {
            submitted.remove(id);
            LOG.debug("Storage audit job {} remains queued in MySQL", id);
        }
    }

    private void run(long id) {
        String token = UUID.randomUUID().toString();
        if (jobs.claim(id, token, lease, MAX_ATTEMPTS) == 0) {
            jobs.exhaust(id, MAX_ATTEMPTS);
            return;
        }
        Integer attempt = jobs.attempts(id, token);
        if (attempt == null) return;
        LOG.info("Storage audit job {} started attempt {}", id, attempt);
        long start = System.nanoTime();
        StorageReconciliation.Report report;
        try {
            report = scanner.scan(grace);
        } catch (Exception failure) {
            String state = attempt >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
            String error = failure.getClass().getSimpleName();
            if (jobs.fail(id, token, state, error, (long) retry * attempt) == 1) {
                LOG.warn("Storage audit job {} ended as {} with {} after {} ms", id, state, error,
                        Duration.ofNanos(System.nanoTime() - start).toMillis());
            }
            return;
        }
        if (jobs.succeed(id, token, report.toString()) == 1) {
            LOG.info("Storage audit job {} succeeded after {} ms", id, Duration.ofNanos(System.nanoTime() - start).toMillis());
        }
    }
}
