package com.filevault.storage;

import com.filevault.FileVaultApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceJobsTest {
    @TempDir Path root;

    @Test
    void executorSaturationRejectsInsteadOfCreatingUnboundedThreads() throws Exception {
        var executor = new MaintenanceConfig().storageMaintenanceExecutor(1, 1, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        executor.initialize();
        try {
            executor.execute(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            executor.execute(() -> {});
            assertEquals(1, executor.getThreadPoolExecutor().getQueue().size());
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FILEVAULT_IT", matches = "true")
    void jobRetriesAfterDiskFailureAndRecoversAnExpiredClaimAfterRestart() throws Exception {
        long period = ThreadLocalRandom.current().nextLong(-Long.MAX_VALUE, -2);
        long id;
        try (var app = start()) {
            var jobs = app.getBean(MaintenanceJobs.class);
            var db = app.getBean(JdbcClient.class);
            jobs.enqueue(period);
            jobs.enqueue(period);
            id = db.sql("SELECT id FROM storage_audit_jobs WHERE period_key=?").param(period).query(Long.class).single();
            var objectDir = root.resolve("objects");
            var parked = root.resolve("test-parked-objects");
            Files.move(objectDir, parked);
            try {
                jobs.submit(id);
                var failed = await(db, id, "PENDING", 1);
                assertEquals("IOException", failed.error());
                assertTrue(failed.delayed());
            } finally {
                Files.move(parked, objectDir);
            }
            db.sql("""
                    UPDATE storage_audit_jobs SET state='RUNNING', attempts=2,
                        lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND, claim_token=UUID() WHERE id=?
                    """).param(id).update();
        }
        try (var app = start()) {
            var jobs = app.getBean(MaintenanceJobs.class);
            var db = app.getBean(JdbcClient.class);
            try {
                jobs.submit(id);
                jobs.submit(id);
                var finished = await(db, id, "SUCCEEDED", 3);
                assertNotNull(finished.report());
                assertTrue(finished.report().contains("orphanObjects="));
                assertNull(finished.error());
            } finally {
                db.sql("DELETE FROM storage_audit_jobs WHERE id=? AND period_key=?").params(id, period).update();
            }
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(FileVaultApplication.class).run("--server.port=0",
                "--filevault.storage-root=" + root, "--filevault.upload-cleanup-enabled=false",
                "--filevault.maintenance.enabled=false");
    }

    private record State(String state, int attempts, String error, String report, boolean delayed) { }

    private static State await(JdbcClient db, long id, String status, int attempts) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        State snapshot;
        do {
            snapshot = db.sql("""
                    SELECT state, attempts, error_kind, report, (available_at > UTC_TIMESTAMP(6)) AS retry_pending
                    FROM storage_audit_jobs WHERE id=?
                    """).param(id).query((rs, row) -> new State(rs.getString("state"), rs.getInt("attempts"),
                    rs.getString("error_kind"), rs.getString("report"), rs.getBoolean("retry_pending"))).single();
            if (status.equals(snapshot.state()) && snapshot.attempts() == attempts) return snapshot;
            Thread.sleep(30);
        } while (System.nanoTime() < end);
        throw new AssertionError("Unexpected final state " + snapshot);
    }
}
