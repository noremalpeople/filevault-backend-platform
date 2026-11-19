package com.filevault.storage;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "filevault.maintenance.enabled", havingValue = "true", matchIfMissing = true)
class MaintenanceSchedule {
    private final MaintenanceJobs jobs;
    private final long period;

    MaintenanceSchedule(MaintenanceJobs jobs, @Value("${filevault.maintenance.period-seconds}") long period) {
        if (period < 1) throw new IllegalArgumentException("Invalid maintenance period.");
        this.jobs = jobs;
        this.period = period;
    }

    @Scheduled(initialDelayString = "${filevault.maintenance.poll-seconds}",
            fixedDelayString = "${filevault.maintenance.poll-seconds}", timeUnit = TimeUnit.SECONDS)
    void poll() {
        jobs.enqueue(Instant.now().getEpochSecond() / period);
        jobs.dispatch();
    }
}
