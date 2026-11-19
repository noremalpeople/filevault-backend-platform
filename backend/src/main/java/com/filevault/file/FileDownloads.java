package com.filevault.file;

import com.filevault.common.TransferTelemetry;
import com.filevault.storage.LocalStorage;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Component
public class FileDownloads {
    private final LocalStorage storage;
    private final TransferTelemetry telemetry;

    public FileDownloads(LocalStorage storage, TransferTelemetry telemetry) {
        this.storage = storage;
        this.telemetry = telemetry;
    }

    public ResponseEntity<StreamingResponseBody> attachment(String name, String storageKey, long size,
                                                           String sha256, HttpHeaders request) {
        String etag = "\"" + sha256 + "\"";
        String rangeHeader = request.getFirst(HttpHeaders.RANGE);
        String ifRange = request.getFirst(HttpHeaders.IF_RANGE);
        boolean wantsRange = rangeHeader != null && (ifRange == null || etag.equals(ifRange));
        long first = 0;
        long last = size - 1;
        if (wantsRange) {
            try {
                if (rangeHeader.indexOf(',') >= 0 || request.get(HttpHeaders.RANGE).size() != 1) {
                    throw new IllegalArgumentException("Use a single range.");
                }
                var ranges = HttpRange.parseRanges(rangeHeader);
                if (ranges.size() != 1) throw new IllegalArgumentException("Use a single range.");
                first = ranges.getFirst().getRangeStart(size);
                last = ranges.getFirst().getRangeEnd(size);
                if (first < 0 || first >= size || last < first) throw new IllegalArgumentException("Unsatisfiable range.");
            } catch (IllegalArgumentException invalidRange) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes").cacheControl(CacheControl.noStore()).eTag(etag).build();
            }
        }
        long length = last - first + 1;
        var response = ResponseEntity.status(wantsRange ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(length)
                .eTag(etag)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer");
        if (wantsRange) response.header(HttpHeaders.CONTENT_RANGE, "bytes " + first + "-" + last + "/" + size);
        long offset = first;
        String requestId = MDC.get("requestId");
        return response.body(output -> stream(storageKey, offset, length, requestId, output));
    }

    private void stream(String key, long offset, long length, String requestId, OutputStream output) throws IOException {
        long started = System.nanoTime();
        boolean success = false;
        try (var context = requestId == null ? null : MDC.putCloseable("requestId", requestId)) {
            try (var input = storage.open(key)) {
                input.skipNBytes(offset);
                copy(input, output, length);
                success = true;
            } finally {
                telemetry.recordTransfer("download", success, started);
            }
        }
    }

    private static void copy(InputStream input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            int bytes = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
            if (bytes < 0) throw new EOFException("Stored object ended before the requested interval.");
            output.write(buffer, 0, bytes);
            remaining -= bytes;
        }
    }
}
