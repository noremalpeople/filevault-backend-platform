package com.filevault.file;

import com.filevault.common.ApiException;
import com.filevault.storage.FileRules;
import com.filevault.storage.LocalStorage;
import com.filevault.user.UserMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FileService {
    public static final long SMALL_FILE_BYTES = 10L * 1024 * 1024;
    private final long userQuota;
    private final TransactionTemplate write;
    private final UserMapper users;
    private final FileMapper files;
    private final LocalStorage storage;
    private final FileStatistics statistics;
    private final ApplicationEventPublisher events;

    public FileService(UserMapper users, FileMapper files, LocalStorage storage, PlatformTransactionManager transactions,
                       FileStatistics statistics, ApplicationEventPublisher events,
                       @Value("${filevault.user-object-quota-bytes}") long userQuota) {
        if (userQuota < 1) throw new IllegalArgumentException("User object quota must be positive.");
        this.users = users;
        this.files = files;
        this.storage = storage;
        this.statistics = statistics;
        this.events = events;
        this.userQuota = userQuota;
        this.write = new TransactionTemplate(transactions);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record FileView(String id, String displayName, String sha256, long sizeBytes, String mediaType,
                           LocalDateTime createdAt) {
        static FileView from(FileMapper.StoredFile file) {
            return new FileView(file.id(), file.displayName(), file.sha256(), file.sizeBytes(),
                    file.mediaType(), file.createdAt());
        }
    }

    public FileView upload(String username, String name, InputStream content) throws IOException {
        try (content) {
            String mediaType = FileRules.validateName(name);
            try (var staged = storage.stage(content, SMALL_FILE_BYTES)) {
                FileRules.validateContent(staged.path(), mediaType);
                long owner = owner(username);
                return write.execute(status -> {
                    try {
                        return persist(owner, name, mediaType, staged);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = IOException.class)
    public FileView persist(long owner, String name, String mediaType, LocalStorage.Staged staged) throws IOException {
        users.lock(owner);
        String file = UUID.randomUUID().toString();
        var candidate = files.reusable(owner, staged.sha256(), staged.bytes());
        if (candidate != null && storage.hasObject(candidate.storageKey())) {
            files.insertFile(file, owner, candidate.id(), name, mediaType);
            events.publishEvent(new FileStatistics.Changed(owner));
            return FileView.from(require(owner, file));
        }
        if (staged.bytes() > userQuota - files.allocatedBytes(owner)) {
            throw new ApiException(HttpStatus.CONFLICT, "QUOTA_EXCEEDED", "The account storage quota is exhausted.");
        }
        String key = UUID.randomUUID().toString();
        String object = UUID.randomUUID().toString();
        storage.publish(staged, key);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    storage.removeObject(key);
                }
            }
        });
        files.insertObject(object, owner, key, staged.sha256(), staged.bytes(), mediaType);
        files.insertFile(file, owner, object, name, mediaType);
        events.publishEvent(new FileStatistics.Changed(owner));
        return FileView.from(require(owner, file));
    }

    public List<FileView> list(String username, int page, int size, String prefix) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Use a nonnegative page and size 1 to 100.");
        }
        long owner = owner(username);
        long offset = (long) page * size;
        if (prefix.isEmpty()) return files.list(owner, size, offset).stream().map(FileView::from).toList();
        if (prefix.length() > 180) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PREFIX", "Use up to 180 characters.");
        String pattern = prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return files.search(owner, pattern, size, offset).stream().map(FileView::from).toList();
    }

    public FileMapper.Statistics statistics(String username) {
        return statistics.get(owner(username));
    }

    public FileView metadata(String username, UUID id) {
        return FileView.from(require(owner(username), id.toString()));
    }

    @Transactional
    public void rename(String username, UUID id, String name) {
        String type = FileRules.validateName(name);
        long owner = owner(username);
        if (!require(owner, id.toString()).mediaType().equals(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TYPE_CHANGE", "The file type cannot change through a rename.");
        }
        if (files.rename(owner, id.toString(), name) == 0) {
            require(owner, id.toString());
        }
        events.publishEvent(new FileStatistics.Changed(owner));
    }

    @Transactional
    public void delete(String username, UUID id) {
        long owner = owner(username);
        if (files.softDelete(owner, id.toString()) == 0) {
            throw missing();
        }
        events.publishEvent(new FileStatistics.Changed(owner));
    }

    public FileMapper.StoredFile download(String username, UUID id) {
        return require(owner(username), id.toString());
    }

    private long owner(String username) {
        var account = users.find(username);
        if (account == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in first.");
        }
        return account.id();
    }

    private FileMapper.StoredFile require(long owner, String id) {
        var file = files.find(owner, id);
        if (file == null) {
            throw missing();
        }
        return file;
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found.");
    }
}
