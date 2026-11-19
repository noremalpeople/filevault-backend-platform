package com.filevault.file;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
class StatisticsCacheConfig {
    @Bean
    CaffeineCacheManager cacheManager(@Value("${filevault.stats-cache-ttl}") Duration ttl,
                                      @Value("${filevault.stats-cache-capacity}") long capacity) {
        if (ttl.isZero() || ttl.isNegative() || capacity < 1) throw new IllegalArgumentException("Invalid statistics cache settings.");
        var manager = new CaffeineCacheManager(FileStatistics.CACHE);
        manager.setAllowNullValues(false);
        manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(capacity).recordStats());
        return manager;
    }
}
