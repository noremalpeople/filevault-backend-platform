package com.filevault.upload;

import com.filevault.FileVaultApplication;
import com.filevault.common.ApiException;
import com.filevault.file.FileService;
import com.filevault.storage.LocalStorage;
import com.filevault.storage.StorageReconciliation;
import com.filevault.storage.TestJunctions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "FILEVAULT_IT", matches = "true")
class MysqlReliabilityTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = UUID.randomUUID().toString();
    @TempDir static Path scratch;
    private static Path storage;
    private static Path web;
    private static ServletWebServerApplicationContext app;
    private final List<String> accounts = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        storage = scratch.resolve("storage");
        web = Files.createDirectories(scratch.resolve("web/assets")).getParent();
        Files.writeString(web.resolve("index.html"), "<!doctype html><script src='/assets/test.js'></script>");
        Files.writeString(web.resolve("assets/test.js"), "export const testFixture = true;");
        app = startWithStorage(storage);
    }

    private static ServletWebServerApplicationContext startWithStorage(Path root) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(FileVaultApplication.class).run(
                "--server.port=0", "--filevault.storage-root=" + root, "--filevault.web-root=" + web.toUri(),
                "--filevault.cleanup-delay-ms=3600000", "--filevault.user-upload-reservation-bytes=12582912",
                "--filevault.user-object-quota-bytes=12582912");
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @AfterEach
    void removeOnlyFixtureRows() {
        for (String name : accounts) {
            Long owner = jdbc().sql("SELECT id FROM users WHERE username = ?").param(name).query(Long.class).optional().orElse(null);
            if (owner == null) continue;
            update("DELETE FROM upload_chunks WHERE session_id IN (SELECT id FROM upload_sessions WHERE owner_id=?)", owner);
            update("DELETE FROM upload_sessions WHERE owner_id=?", owner);
            update("DELETE FROM share_links WHERE owner_id=?", owner);
            update("DELETE FROM user_files WHERE owner_id=?", owner);
            update("DELETE FROM file_objects WHERE owner_id=?", owner);
            update("DELETE FROM users WHERE id=?", owner);
        }
    }

    @Test
    void servesVueAssetsAndRejectsPrivatePathsAndNestedJunction() throws Exception {
        var anonymous = new Client();
        assertTrue(new String(anonymous.request("GET", "/", null, null, false, 200)).contains("/assets/test.js"));
        anonymous.request("GET", "/assets/test.js", null, null, false, 200);
        Client alice = account();
        String file = publish(alice, "private.txt", bytes("test-private-data"));
        String key = objectKey(file);
        anonymous.request("GET", "/api/v1/files/" + file + "/download", null, null, false, 401);
        anonymous.request("GET", "/objects/" + key, null, null, false, 401);
        alice.request("GET", "/objects/" + key, null, null, false, 404);
        Path alias = web.resolve("assets/private-fixture");
        TestJunctions.create(alias, storage.resolve("objects"));
        try {
            anonymous.request("GET", "/assets/private-fixture/" + key, null, null, false, 404);
        } finally {
            Files.delete(alias);
        }
    }

    @Test
    void refusesStartupWhenStorageIsInsideTheWebRoot() {
        Path overlap = web.resolve("nested-private");
        var error = assertThrows(RuntimeException.class, () -> {
            try (var invalid = startWithStorage(overlap)) {
                fail("Static and private directories overlap.");
            }
        });
        assertTrue(causes(error, "private storage overlaps"));
        assertFalse(Files.exists(overlap));
    }

    @Test
    void deniesMissingAndIncorrectCsrfAndCrossOwnerOperations() throws Exception {
        Client alice = account();
        Client bob = account();
        byte[] content = bytes("owner isolation");
        String file = publish(alice, "only-alice.txt", content);
        String upload = declare(alice, "pending.txt", content, digest(content));
        JsonNode share = share(alice, file);
        alice.request("DELETE", "/api/v1/files/" + file, null, null, false, 403);
        alice.requestWithToken("DELETE", "/api/v1/files/" + file, null, null, "wrong-token", 403);
        bob.get("/api/v1/files/" + file, 404);
        bob.get("/api/v1/files/" + file + "/download", 404);
        bob.get("/api/v1/uploads/" + upload, 404);
        put(bob, upload, 0, content, 404);
        bob.get("/api/v1/files/" + file + "/shares", 404);
        bob.post("/api/v1/shares", Map.of("fileId", file, "expiresInMinutes", 20), 404);
        bob.request("DELETE", "/api/v1/shares/" + share.get("id").asText(), null, null, true, 404);
        alice.post("/api/v1/uploads", Map.of("filename", "../unsafe.txt", "totalSize", content.length,
                "expectedSha256", digest(content)), 400);
        alice.get("/api/v1/files/" + file, 200);
    }

    @Test
    void detectsAndRepairsMissingAndSameLengthCorruptChunksAndVerifiesDownload() throws Exception {
        Client alice = account();
        int boundary = ChunkLayout.CHUNK_BYTES;
        byte[] data = new byte[boundary + 11];
        Arrays.fill(data, (byte) 'x');
        byte[] first = Arrays.copyOfRange(data, 0, boundary);
        byte[] last = Arrays.copyOfRange(data, boundary, data.length);
        String upload = declare(alice, "repair.txt", data, digest(data));
        put(alice, upload, 0, first, 204);
        put(alice, upload, 1, last, 204);
        Path firstPath = chunkPath(upload, 0);
        Path lastPath = chunkPath(upload, 1);
        Files.delete(firstPath);
        Files.write(lastPath, bytes("wrong-bytes"));
        JsonNode state = alice.get("/api/v1/uploads/" + upload, 200);
        assertEquals(0, state.get("received").size());
        assertEquals("MISSING", state.get("repair").get(0).get("reason").asText());
        assertEquals("CORRUPT", state.get("repair").get(1).get("reason").asText());
        assertEquals("CHUNK_REPAIR_REQUIRED", complete(alice, upload, 409).get("code").asText());
        put(alice, upload, 1, bytes("third-bytes"), 409);
        put(alice, upload, 0, first, 204);
        put(alice, upload, 1, last, 204);
        put(alice, upload, 1, last, 204);
        assertEquals(lastPath, chunkPath(upload, 1));
        assertEquals(2, number("SELECT COUNT(*) FROM upload_chunks WHERE session_id=?", upload));
        state = alice.get("/api/v1/uploads/" + upload, 200);
        assertEquals(2, state.get("received").size());
        assertEquals(0, state.get("repair").size());
        String file = complete(alice, upload, 200).get("fileId").asText();
        byte[] downloaded = alice.request("GET", "/api/v1/files/" + file + "/download", null, null, false, 200);
        assertEquals(digest(data), digest(downloaded));
        assertArrayEquals(data, downloaded);
    }

    @Test
    void badWholeFileHashClosesSessionWithoutPublishingAndAllowsNewSession() throws Exception {
        Client alice = account();
        byte[] data = bytes("real upload");
        String upload = declare(alice, "wrong.txt", data, digest(bytes("other")));
        put(alice, upload, 0, data, 204);
        assertEquals("FILE_HASH_MISMATCH", complete(alice, upload, 422).get("code").asText());
        assertEquals("FAILED", alice.get("/api/v1/uploads/" + upload, 200).get("status").asText());
        assertEquals("UPLOAD_FAILED", complete(alice, upload, 409).get("code").asText());
        assertEquals(0, alice.get("/api/v1/files", 200).size());
        assertNotNull(publish(alice, "correct.txt", data));
    }

    @Test
    void twoConcurrentCompletionsReturnOneFileAndOneObject() throws Exception {
        Client alice = account();
        byte[] data = new byte[ChunkLayout.CHUNK_BYTES * 2];
        Arrays.fill(data, (byte) 'z');
        byte[] part = Arrays.copyOf(data, ChunkLayout.CHUNK_BYTES);
        String upload = declare(alice, "concurrent.txt", data, digest(data));
        put(alice, upload, 0, part, 204);
        put(alice, upload, 1, part, 204);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = pool.submit(() -> { go.await(); return complete(alice, upload, 200).get("fileId").asText(); });
            var two = pool.submit(() -> { go.await(); return complete(alice, upload, 200).get("fileId").asText(); });
            go.countDown();
            assertEquals(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, alice.get("/api/v1/files", 200).size());
        assertEquals(1, number("SELECT COUNT(*) FROM file_objects JOIN users ON owner_id=users.id WHERE username=?", alice.name));
    }

    @Test
    void cancellationCanCommitWhileUploadIsStillReadingAndLatePutDoesNotPublish() throws Exception {
        Client alice = account();
        byte[] data = bytes("slow-body");
        String upload = declare(alice, "slow.txt", data, digest(data));
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stream = new ByteArrayInputStream(data) {
            @Override
            public int read(byte[] buf, int offset, int length) {
                reading.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out in slow stream.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return super.read(buf, offset, length);
            }
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var uploadResult = pool.submit(() -> {
                app.getBean(UploadService.class).put(alice.name, UUID.fromString(upload), 0, stream);
                return null;
            });
            try {
                assertTrue(reading.await(10, TimeUnit.SECONDS));
                var abort = pool.submit(() -> alice.request("POST", "/api/v1/uploads/" + upload + "/abort", null, null, true, 204));
                abort.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            var rejected = assertThrows(ExecutionException.class, () -> uploadResult.get(15, TimeUnit.SECONDS));
            assertEquals("UPLOAD_CLOSED", assertInstanceOf(ApiException.class, rejected.getCause()).code());
        }
        assertEquals("ABORTED", alice.get("/api/v1/uploads/" + upload, 200).get("status").asText());
        assertEquals(0, number("SELECT COUNT(*) FROM upload_chunks WHERE session_id=?", upload));
    }

    @Test
    void resumesAfterStoppingAndRestartingRealServer() throws Exception {
        Client alice = account();
        byte[] data = new byte[ChunkLayout.CHUNK_BYTES + 1];
        Arrays.fill(data, (byte) 'r');
        String upload = declare(alice, "restart.txt", data, digest(data));
        put(alice, upload, 0, Arrays.copyOf(data, ChunkLayout.CHUNK_BYTES), 204);
        app.close();
        app = startWithStorage(storage);
        Client reauthenticated = new Client();
        reauthenticated.login(alice.name);
        JsonNode received = reauthenticated.get("/api/v1/uploads/" + upload, 200).get("received");
        assertEquals(1, received.size());
        assertEquals(0, received.get(0).asInt());
        put(reauthenticated, upload, 1, new byte[]{'r'}, 204);
        String file = complete(reauthenticated, upload, 200).get("fileId").asText();
        assertArrayEquals(data, reauthenticated.request("GET", "/api/v1/files/" + file + "/download", null, null, false, 200));
    }

    @Test
    void anonymousShareDownloadExpiresAndStopsAfterRevocationOrSoftDelete() throws Exception {
        Client alice = account();
        byte[] data = bytes("sharing integration");
        String file = publish(alice, "shared.txt", data);
        var guest = new Client();
        JsonNode live = share(alice, file);
        String link = live.get("url").asText();
        assertArrayEquals(data, guest.request("GET", link + "/download", null, null, false, 200));
        alice.request("DELETE", "/api/v1/shares/" + live.get("id").asText(), null, null, true, 204);
        guest.get(link, 404);
        JsonNode expired = share(alice, file);
        update("UPDATE share_links SET created_at=UTC_TIMESTAMP(6)-INTERVAL 2 MINUTE, "
                + "expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", expired.get("id").asText());
        guest.request("GET", expired.get("url").asText() + "/download", null, null, false, 404);
        JsonNode deleted = share(alice, file);
        alice.request("DELETE", "/api/v1/files/" + file, null, null, true, 204);
        guest.request("GET", deleted.get("url").asText() + "/download", null, null, false, 404);
    }

    @Test
    void reconciliationFindsOrphansAndMissingReferencesWithGraceAndNeverDeletes() throws Exception {
        var scanner = app.getBean(StorageReconciliation.class);
        Duration grace = Duration.ofMinutes(5);
        var baseline = scanner.scan(grace);
        Client alice = account();
        byte[] data = bytes("reconcile fixture");
        String object = objectKey(publish(alice, "audit.txt", data));
        String upload = declare(alice, "pending-audit.txt", data, digest(data));
        put(alice, upload, 0, data, 204);
        Files.delete(storage.resolve("objects").resolve(object));
        Files.delete(chunkPath(upload, 0));
        Path oldObject = synthetic(storage.resolve("objects"), UUID.randomUUID().toString(), true);
        Path freshObject = synthetic(storage.resolve("objects"), UUID.randomUUID().toString(), false);
        Path oldChunk = synthetic(storage.resolve("chunks"), UUID.randomUUID().toString(), true);
        Path agedStage = synthetic(storage.resolve("staging"), "incoming-test.part", true);

        var report = scanner.scan(grace);
        assertEquals(baseline.orphanObjects() + 1, report.orphanObjects());
        assertEquals(baseline.orphanChunks() + 1, report.orphanChunks());
        assertEquals(baseline.missingObjects() + 1, report.missingObjects());
        assertEquals(baseline.missingOpenChunks() + 1, report.missingOpenChunks());
        assertEquals(baseline.agedStagingCandidates() + 1, report.agedStagingCandidates());
        assertTrue(Files.exists(oldObject) && Files.exists(freshObject) && Files.exists(oldChunk) && Files.exists(agedStage));
        assertEquals(1, number("SELECT COUNT(*) FROM file_objects WHERE storage_key=?", object));
    }

    @Test
    void reconciliationModeStartsWithoutHttpMigrationsOrScheduledCleanup() {
        try (var audit = new SpringApplicationBuilder(FileVaultApplication.class).web(WebApplicationType.NONE).run(
                "--filevault.reconcile-only=true", "--spring.flyway.enabled=false",
                "--filevault.storage-root=" + storage)) {
            assertFalse(audit instanceof ServletWebServerApplicationContext);
            assertTrue(audit.getBeansOfType(Flyway.class).isEmpty());
            assertTrue(audit.getBeansOfType(UploadCleanup.class).isEmpty());
        }
    }

    @Test
    void uploadReservationAndSessionCountAreEnforcedWithConfiguredLimits() throws Exception {
        Client alice = account();
        String sha = digest(bytes("fixture"));
        String first = declare(alice, "large.txt", 8 * 1024 * 1024, sha);
        var second = Map.of("filename", "second.txt", "totalSize", 8 * 1024 * 1024, "expectedSha256", sha);
        assertEquals("UPLOAD_RESERVATION_EXCEEDED", alice.post("/api/v1/uploads", second, 409).get("code").asText());
        alice.request("POST", "/api/v1/uploads/" + first + "/abort", null, null, true, 204);
        alice.post("/api/v1/uploads", second, 201);
        for (int index = 0; index < 3; index++) declare(alice, "tiny.txt", new byte[1], sha);
        var fifth = Map.of("filename", "fifth.txt", "totalSize", 1, "expectedSha256", sha);
        assertEquals("SESSION_LIMIT", alice.post("/api/v1/uploads", fifth, 409).get("code").asText());
    }

    @Test
    void cleanupRetryDefersAFixtureRowAndMakesItEligibleAgain() throws Exception {
        Client alice = account();
        byte[] data = bytes("retry fixture");
        String upload = declare(alice, "retry.txt", data, digest(data));
        put(alice, upload, 0, data, 204);
        alice.request("POST", "/api/v1/uploads/" + upload + "/abort", null, null, true, 204);
        var mapper = app.getBean(UploadMapper.class);
        var clean = app.getBean(UploadCleanup.class);
        Path path = chunkPath(upload, 0);
        Files.delete(path);
        Files.createDirectory(path);
        clean.cleanChunk(mapper.chunk(upload, 0));
        assertEquals(1, number("SELECT COUNT(*) FROM upload_chunks WHERE session_id=? AND cleanup_after > UTC_TIMESTAMP(6)", upload));
        assertTrue(Files.isDirectory(path));
        Files.delete(path);
        Files.write(path, data);
        update("UPDATE upload_chunks SET cleanup_after=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE session_id=?", upload);
        clean.cleanChunk(mapper.chunk(upload, 0));
        assertEquals(0, number("SELECT COUNT(*) FROM upload_chunks WHERE session_id=?", upload));
        assertFalse(Files.exists(path));
    }

    @Test
    void objectQuotaCountsPhysicalObjectsAndPreservesDeduplication() throws Exception {
        Client alice = account();
        byte[] same = new byte[8 * 1024 * 1024];
        Arrays.fill(same, (byte) 'a');
        String first = smallUpload(alice, "original.txt", same, 201).get("id").asText();
        String second = smallUpload(alice, "copy.txt", same, 201).get("id").asText();
        assertNotEquals(first, second);
        assertEquals(objectKey(first), objectKey(second));
        byte[] different = new byte[5 * 1024 * 1024];
        Arrays.fill(different, (byte) 'b');
        assertEquals("QUOTA_EXCEEDED", smallUpload(alice, "other.txt", different, 409).get("code").asText());
        assertEquals(2, alice.get("/api/v1/files", 200).size());
    }

    @Test
    void databaseRollbackCompensatesPublishedPhysicalObject() throws Exception {
        Client alice = account();
        long owner = number("SELECT id FROM users WHERE username=?", alice.name);
        byte[] data = bytes("transaction compensation fixture");
        var files = app.getBean(FileService.class);
        var transactions = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        Path published;
        try (var staged = app.getBean(LocalStorage.class).stage(new ByteArrayInputStream(data), data.length)) {
            published = transactions.execute(tx -> {
                try {
                    var file = files.persist(owner, "rollback.txt", "text/plain", staged);
                    Path path = storage.resolve("objects").resolve(objectKey(file.id()));
                    assertTrue(Files.exists(path));
                    tx.setRollbackOnly();
                    return path;
                } catch (java.io.IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        }
        assertNotNull(published);
        assertFalse(Files.exists(published));
        assertEquals(0, alice.get("/api/v1/files", 200).size());
        assertEquals(0, number("SELECT COUNT(*) FROM file_objects WHERE storage_key=?", published.getFileName().toString()));
    }

    private static boolean causes(Throwable error, String expected) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(expected)) return true;
        }
        return false;
    }

    private static Path synthetic(Path dir, String name, boolean age) throws Exception {
        Path path = Files.writeString(dir.resolve(name), "fixture");
        if (age) Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        return path;
    }

    private static JdbcClient jdbc() { return app.getBean(JdbcClient.class); }

    private static void update(String sql, Object value) { jdbc().sql(sql).param(value).update(); }

    private static long number(String sql, String value) { return jdbc().sql(sql).param(value).query(Long.class).single(); }

    private static String objectKey(String file) {
        return jdbc().sql("SELECT o.storage_key FROM file_objects o JOIN user_files f ON f.object_id=o.id WHERE f.id=?")
                .param(file).query(String.class).single();
    }

    private static Path chunkPath(String session, int index) {
        String key = jdbc().sql("SELECT stored_key FROM upload_chunks WHERE session_id=? AND chunk_index=?")
                .params(session, index).query(String.class).single();
        return storage.resolve("chunks").resolve(key);
    }

    private Client account() throws Exception {
        var client = new Client();
        String name = "f5_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        client.refreshCsrf();
        client.post("/api/v1/auth/register", Map.of("username", name, "password", PASSWORD), 201);
        accounts.add(name);
        client.login(name);
        return client;
    }

    private static byte[] bytes(String input) { return input.getBytes(StandardCharsets.UTF_8); }

    private static String digest(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    private static String declare(Client client, String name, byte[] data, String sha) throws Exception {
        return declare(client, name, data.length, sha);
    }

    private static String declare(Client client, String name, long size, String sha) throws Exception {
        return client.post("/api/v1/uploads", Map.of("filename", name, "totalSize", size, "expectedSha256", sha), 201)
                .get("id").asText();
    }

    private static void put(Client client, String id, int index, byte[] bytes, int status) throws Exception {
        client.request("PUT", "/api/v1/uploads/" + id + "/chunks/" + index, bytes, "application/octet-stream", true, status);
    }

    private static JsonNode complete(Client client, String id, int status) throws Exception {
        return JSON.readTree(client.request("POST", "/api/v1/uploads/" + id + "/complete", null, null, true, status));
    }

    private static String publish(Client client, String name, byte[] data) throws Exception {
        String id = declare(client, name, data, digest(data));
        put(client, id, 0, data, 204);
        return complete(client, id, 200).get("fileId").asText();
    }

    private static JsonNode share(Client client, String file) throws Exception {
        return client.post("/api/v1/shares", Map.of("fileId", file, "expiresInMinutes", 20), 201);
    }

    private static JsonNode smallUpload(Client client, String name, byte[] data, int status) throws Exception {
        String boundary = "FileVaultIT-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.writeBytes(bytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + name + "\"\r\nContent-Type: text/plain\r\n\r\n"));
        body.writeBytes(data);
        body.writeBytes(bytes("\r\n--" + boundary + "--\r\n"));
        return JSON.readTree(client.request("POST", "/api/v1/files", body.toByteArray(),
                "multipart/form-data; boundary=" + boundary, true, status));
    }

    private static final class Client {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(5)).build();
        private String name;
        private String csrfHeader;
        private String csrf;

        void login(String name) throws Exception {
            this.name = name;
            refreshCsrf();
            request("POST", "/api/v1/auth/login", bytes("username=" + name + "&password=" + PASSWORD),
                    "application/x-www-form-urlencoded", true, 204);
            refreshCsrf();
        }

        void refreshCsrf() throws Exception {
            JsonNode csrfJson = get("/api/v1/auth/csrf", 200);
            csrfHeader = csrfJson.get("headerName").asText();
            csrf = csrfJson.get("token").asText();
        }

        JsonNode get(String path, int status) throws Exception {
            return JSON.readTree(request("GET", path, null, null, false, status));
        }

        JsonNode post(String path, Object body, int status) throws Exception {
            return JSON.readTree(request("POST", path, JSON.writeValueAsBytes(body), "application/json", true, status));
        }

        byte[] request(String method, String path, byte[] body, String type, boolean sendCsrf, int status) throws Exception {
            return requestWithToken(method, path, body, type, sendCsrf ? csrf : null, status);
        }

        byte[] requestWithToken(String method, String path, byte[] body, String type, String token, int status) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + path))
                    .timeout(Duration.ofSeconds(25))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            if (type != null) request.header("Content-Type", type);
            if (token != null) request.header(csrfHeader, token);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(status, response.statusCode(), method + " response status");
            return response.body();
        }
    }
}
