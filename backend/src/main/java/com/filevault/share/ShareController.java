package com.filevault.share;

import com.filevault.file.FileDownloads;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
class ShareController {
    private final ShareService shares;
    private final FileDownloads downloads;

    ShareController(ShareService shares, FileDownloads downloads) {
        this.shares = shares;
        this.downloads = downloads;
    }

    record Request(@NotNull UUID fileId, @Min(1) @Max(10080) int expiresInMinutes) { }

    record Summary(String displayName, long sizeBytes, String mediaType, LocalDateTime expiresAt) { }

    @GetMapping("/api/v1/files/{id}/shares")
    ResponseEntity<List<ShareMapper.ActiveShare>> list(Principal principal, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(shares.listActive(principal.getName(), id));
    }

    @PostMapping("/api/v1/shares")
    ResponseEntity<ShareService.Created> create(Principal principal, @Valid @RequestBody Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(shares.create(principal.getName(), request.fileId(), request.expiresInMinutes()));
    }

    @DeleteMapping("/api/v1/shares/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(Principal principal, @PathVariable UUID id) {
        shares.revoke(principal.getName(), id);
    }

    @GetMapping("/api/v1/public/shares/{token}")
    ResponseEntity<Summary> summary(@PathVariable String token) {
        var file = shares.resolve(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(new Summary(file.name(), file.sizeBytes(), file.mediaType(), file.expiresAt()));
    }

    @GetMapping("/api/v1/public/shares/{token}/download")
    ResponseEntity<StreamingResponseBody> download(@PathVariable String token, @RequestHeader HttpHeaders headers) {
        var file = shares.resolve(token);
        return downloads.attachment(file.name(), file.storageKey(), file.sizeBytes(), file.sha256(), headers);
    }
}
