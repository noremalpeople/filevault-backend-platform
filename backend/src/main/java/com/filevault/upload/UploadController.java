package com.filevault.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/uploads")
class UploadController {
    private final UploadService uploads;

    UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    record Declaration(@NotBlank String filename, long totalSize,
                       @NotNull @Pattern(regexp = "[0-9a-f]{64}") String expectedSha256) { }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UploadService.Detail create(Principal principal, @Valid @RequestBody Declaration request) {
        return uploads.create(principal.getName(), request.filename(), request.totalSize(), request.expectedSha256());
    }

    @GetMapping("/{id}")
    UploadService.Detail get(Principal principal, @PathVariable UUID id) {
        return uploads.get(principal.getName(), id);
    }

    @PutMapping(value = "/{id}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void put(Principal principal, @PathVariable UUID id, @PathVariable int index, HttpServletRequest request)
            throws IOException {
        try (var body = request.getInputStream()) {
            uploads.put(principal.getName(), id, index, body);
        }
    }

    @PostMapping("/{id}/complete")
    Map<String, String> complete(Principal principal, @PathVariable UUID id) throws IOException {
        return Map.of("fileId", uploads.complete(principal.getName(), id));
    }

    @PostMapping("/{id}/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void abort(Principal principal, @PathVariable UUID id) {
        uploads.abort(principal.getName(), id);
    }
}
