package com.filevault.file;

import com.filevault.storage.LocalStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService files;
    private final LocalStorage storage;

    public FileController(FileService files, LocalStorage storage) {
        this.files = files;
        this.storage = storage;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    FileService.FileView upload(Principal principal, @RequestPart MultipartFile file) throws IOException {
        return files.upload(principal.getName(), file.getOriginalFilename(), file.getInputStream());
    }

    @GetMapping
    List<FileService.FileView> list(Principal principal, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return files.list(principal.getName(), page, size);
    }

    @GetMapping("/{id}")
    FileService.FileView metadata(Principal principal, @PathVariable UUID id) {
        return files.metadata(principal.getName(), id);
    }

    @GetMapping("/{id}/download")
    ResponseEntity<StreamingResponseBody> download(Principal principal, @PathVariable UUID id) {
        var file = files.download(principal.getName(), id);
        StreamingResponseBody body = output -> {
            try (var input = storage.open(file.storageKey())) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.displayName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    record Rename(@NotBlank String displayName) { }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void rename(Principal principal, @PathVariable UUID id, @Valid @RequestBody Rename request) {
        files.rename(principal.getName(), id, request.displayName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Principal principal, @PathVariable UUID id) {
        files.delete(principal.getName(), id);
    }
}
