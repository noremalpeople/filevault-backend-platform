package com.filevault.file;

import com.filevault.storage.LocalStorage;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Component
public class FileDownloads {
    private final LocalStorage storage;

    public FileDownloads(LocalStorage storage) {
        this.storage = storage;
    }

    public ResponseEntity<StreamingResponseBody> attachment(String name, String storageKey, long size) {
        StreamingResponseBody body = output -> {
            try (var input = storage.open(storageKey)) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }
}
