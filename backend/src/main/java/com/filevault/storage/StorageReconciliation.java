package com.filevault.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class StorageReconciliation {
    private final LocalStorage storage;
    private final JdbcClient jdbc;

    public StorageReconciliation(LocalStorage storage, JdbcClient jdbc) {
        this.storage = storage;
        this.jdbc = jdbc;
    }

    public record Report(long orphanObjects, long orphanChunks, long missingObjects,
                         long missingOpenChunks, long agedStagingCandidates) { }

    private record Reference(String kind, String key, boolean expected) { }

    public Report scan(Duration grace) throws IOException {
        if (grace.isZero() || grace.isNegative()) throw new IllegalArgumentException("Storage scan grace must be positive.");
        Instant cutoff = Instant.now().minus(grace);
        Path objects = storage.auditDirectory("objects");
        Path chunks = storage.auditDirectory("chunks");
        Set<String> orphanObjects = agedFiles(objects, cutoff, "[a-f0-9-]{36}");
        Set<String> orphanChunks = agedFiles(chunks, cutoff, "[a-f0-9-]{36}");
        var refs = jdbc.sql("""
                SELECT 'objects' AS kind, storage_key AS item_key, 1 AS expected FROM file_objects
                UNION ALL
                SELECT 'chunks', c.stored_key, (s.status = 'OPEN' AND s.expires_at > UTC_TIMESTAMP(6))
                FROM upload_chunks c JOIN upload_sessions s ON s.id = c.session_id
                """).query((rs, row) -> new Reference(rs.getString("kind"), rs.getString("item_key"),
                        rs.getBoolean("expected"))).list();
        long missingObjects = 0;
        long missingChunks = 0;
        for (var ref : refs) {
            if (!ref.key().matches("[a-f0-9-]{36}")) throw new IOException("Unexpected database storage key.");
            if (ref.kind().equals("objects")) {
                orphanObjects.remove(ref.key());
                if (!regular(objects.resolve(ref.key()))) missingObjects++;
            } else {
                orphanChunks.remove(ref.key());
                if (ref.expected() && !regular(chunks.resolve(ref.key()))) missingChunks++;
            }
        }
        Path staging = storage.auditDirectory("staging");
        long staged = agedFiles(staging, cutoff, "incoming-.*\\.part").stream()
                .map(staging::resolve).filter(path -> !storage.isStagedInUse(path)).count();
        return new Report(orphanObjects.size(), orphanChunks.size(), missingObjects, missingChunks, staged);
    }

    private static Set<String> agedFiles(Path directory, Instant cutoff, String namePattern) throws IOException {
        Set<String> result = new HashSet<>();
        try (var entries = Files.newDirectoryStream(directory, path -> path.getFileName().toString().matches(namePattern))) {
            for (Path path : entries) {
                var attrs = attributes(path);
                if (attrs != null && attrs.isRegularFile() && attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                    result.add(path.getFileName().toString());
                }
            }
        }
        return result;
    }

    private static boolean regular(Path path) throws IOException {
        var attrs = attributes(path);
        return attrs != null && attrs.isRegularFile();
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException removedDuringScan) {
            return null;
        }
    }

    @Bean
    @ConditionalOnProperty(name = "filevault.reconcile-only", havingValue = "true")
    ApplicationRunner reportStorage(@Value("${filevault.reconcile-grace-seconds}") long seconds) {
        return args -> System.out.println("FileVault storage reconciliation (read-only): " + scan(Duration.ofSeconds(seconds)));
    }
}
