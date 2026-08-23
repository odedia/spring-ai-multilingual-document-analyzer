package com.odedia.analyzer.jobs;

import com.odedia.repo.jpa.MessageSummaryCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled job to clean up stale message summary cache entries.
 *
 * Runs daily at 2 AM to:
 * - Delete entries not accessed recently
 * - Free up database space
 * - Prevent unbounded cache growth
 */
@Component
@Profile("!mcp")
public class SummaryCacheCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(SummaryCacheCleanupJob.class);

    private final MessageSummaryCacheRepository cacheRepository;
    private final int maxAgeDays;
    private final boolean enabled;

    public SummaryCacheCleanupJob(
            MessageSummaryCacheRepository cacheRepository,
            @Value("${app.ai.cache.maxAgeDays:7}") int maxAgeDays,
            @Value("${app.ai.cache.cleanupEnabled:true}") boolean enabled) {
        this.cacheRepository = cacheRepository;
        this.maxAgeDays = maxAgeDays;
        this.enabled = enabled;

        logger.info("Initialized SummaryCacheCleanupJob: enabled={}, maxAgeDays={}", enabled, maxAgeDays);
    }

    /**
     * Runs daily at 2 AM to clean up stale cache entries.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupStaleCache() {
        if (!enabled) {
            logger.debug("Cache cleanup is disabled, skipping");
            return;
        }

        Instant cutoffTime = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);

        logger.info("Starting cache cleanup. Removing entries not accessed since: {}", cutoffTime);

        int deletedCount = cacheRepository.deleteByLastAccessedAtBefore(cutoffTime);

        logger.info("Cache cleanup complete. Deleted {} stale entries", deletedCount);
    }

    /**
     * Manual trigger for cache cleanup (for admin use).
     * 
     * @return Number of entries deleted
     */
    @Transactional
    public int triggerManualCleanup() {
        Instant cutoffTime = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);
        int deletedCount = cacheRepository.deleteByLastAccessedAtBefore(cutoffTime);
        logger.info("Manual cache cleanup complete. Deleted {} stale entries", deletedCount);
        return deletedCount;
    }
}
