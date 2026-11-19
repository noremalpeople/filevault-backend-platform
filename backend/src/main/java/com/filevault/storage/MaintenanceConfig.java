package com.filevault.storage;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@ConditionalOnWebApplication
class MaintenanceConfig {
    @Bean(name = "storageMaintenanceExecutor", defaultCandidate = false)
    ThreadPoolTaskExecutor storageMaintenanceExecutor(@Value("${filevault.maintenance.core}") int core,
                                                       @Value("${filevault.maintenance.max}") int max,
                                                       @Value("${filevault.maintenance.queue}") int queue) {
        if (core < 1 || max < core || queue < 1) throw new IllegalArgumentException("Invalid maintenance executor limits.");
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("vault-maintenance-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }
}
