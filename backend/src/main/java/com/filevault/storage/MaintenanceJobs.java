package com.filevault.storage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meters;

    MaintenanceJobs(MaintenanceJobMapper jobs, StorageReconciliation scanner, MeterRegistry meters,
                    @Qualifier("storageMaintenanceExecutor") ThreadPoolTaskExecutor executor,
                    @Value("${filevault.reconcile-grace-seconds}") long grace,
                    @Value("${filevault.maintenance.lease-seconds}") int lease,
                    @Value("${filevault.maintenance.retry-seconds}") int retry) {
        if (grace < 1 || lease < 1 || retry < 1) throw new IllegalArgumentException("Invalid maintenance timing.");
        this.jobs = jobs;
        this.scanner = scanner;
        this.executor = executor;
        this.meters = meters;
        this.grace = Duration.ofSeconds(grace);
        this.lease = lease;
        this.retry = retry;
        Gauge.builder("filevault.maintenance.executor.active", executor, ThreadPoolTaskExecutor::getActiveCount).register(meters);
        Gauge.builder("filevault.maintenance.executor.queued", executor,
                pool -> pool.getThreadPoolExecutor().getQueue().size()).register(meters);
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
                    LOG.atWarn().addKeyValue("jobId", id).addKeyValue("errorKind", failure.getClass().getSimpleName())
                            .log("Storage audit coordination failed");
                } finally {
                    submitted.remove(id);
                }
            });
        } catch (RejectedExecutionException busy) {
            submitted.remove(id);
            meters.counter("filevault.maintenance.rejected").increment();
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
        LOG.atInfo().addKeyValue("jobId", id).addKeyValue("attempt", attempt).log("Storage audit started");
        long start = System.nanoTime();
        StorageReconciliation.Report report;
        try {
            report = scanner.scan(grace);
        } catch (Exception failure) {
            String state = attempt >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
            String error = failure.getClass().getSimpleName();
            if (jobs.fail(id, token, state, error, (long) retry * attempt) == 1) {
                meters.counter("filevault.maintenance.attempts", "outcome", "failure").increment();
                LOG.atWarn().addKeyValue("jobId", id).addKeyValue("state", state).addKeyValue("errorKind", error)
                        .addKeyValue("durationMs", Duration.ofNanos(System.nanoTime() - start).toMillis())
                        .log("Storage audit attempt failed");
            }
            return;
        }
        if (jobs.succeed(id, token, report.toString()) == 1) {
            meters.counter("filevault.maintenance.attempts", "outcome", "success").increment();
            LOG.atInfo().addKeyValue("jobId", id)
                    .addKeyValue("durationMs", Duration.ofNanos(System.nanoTime() - start).toMillis())
                    .log("Storage audit succeeded");
        }
    }
}
