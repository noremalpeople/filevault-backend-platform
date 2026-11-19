package com.filevault.file;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
class FileStatistics {
    static final String CACHE = "fileStatistics";
    record Changed(long owner) { }

    private final FileMapper files;
    private final CacheManager caches;

    FileStatistics(FileMapper files, CacheManager caches) {
        this.files = files;
        this.caches = caches;
    }

    @Cacheable(cacheNames = CACHE, key = "#p0", sync = true)
    public FileMapper.Statistics get(long owner) {
        return files.statistics(owner);
    }

    @TransactionalEventListener
    public void invalidate(Changed change) {
        var cache = caches.getCache(CACHE);
        if (cache != null) cache.evict(change.owner());
    }
}
