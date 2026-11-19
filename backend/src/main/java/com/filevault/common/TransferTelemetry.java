package com.filevault.common;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TransferTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(TransferTelemetry.class);
    private final MeterRegistry meters;

    public TransferTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    public void uploadRequest(boolean success, long started) {
        String outcome = success ? "success" : "failure";
        meters.counter("filevault.upload.requests", "outcome", outcome).increment();
        log("upload", outcome, System.nanoTime() - started);
    }

    public void recordTransfer(String operation, boolean success, long started) {
        String outcome = success ? "success" : "failure";
        long elapsed = System.nanoTime() - started;
        meters.timer("filevault." + operation + ".duration", "outcome", outcome).record(elapsed, TimeUnit.NANOSECONDS);
        log(operation, outcome, elapsed);
    }

    private static void log(String operation, String outcome, long elapsed) {
        LOG.atInfo().addKeyValue("operation", operation).addKeyValue("outcome", outcome)
                .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(elapsed)).log("File transfer");
    }
}
