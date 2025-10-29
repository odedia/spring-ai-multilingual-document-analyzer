package com.odedia.repo.jpa;

import com.odedia.repo.model.MessageSummaryCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for managing cached message summaries.
 * Provides efficient lookup and cleanup operations.
 */
public interface MessageSummaryCacheRepository extends JpaRepository<MessageSummaryCache, String> {

    /**
     * Finds a cached summary by conversation ID and message range hash.
     *
     * @param conversationId The conversation ID
     * @param messageRangeHash Hash of the message IDs
     * @return Optional containing the cached summary if found
     */
    Optional<MessageSummaryCache> findByConversationIdAndMessageRangeHash(
            String conversationId,
            String messageRangeHash
    );

    /**
     * Deletes all cached summaries for a specific conversation.
     * Useful when a conversation is deleted.
     *
     * @param conversationId The conversation ID
     */
    void deleteByConversationId(String conversationId);

    /**
     * Updates the last accessed timestamp for cache entry (for LRU tracking).
     *
     * @param id The cache entry ID
     * @param lastAccessedAt The new timestamp
     */
    @Modifying
    @Query("UPDATE MessageSummaryCache c SET c.lastAccessedAt = :lastAccessedAt WHERE c.id = :id")
    void updateLastAccessedAt(@Param("id") String id, @Param("lastAccessedAt") Instant lastAccessedAt);

    /**
     * Deletes old cache entries that haven't been accessed in a while.
     * Helps prevent unbounded cache growth.
     *
     * @param cutoffTime Delete entries older than this
     * @return Number of deleted entries
     */
    @Modifying
    @Query("DELETE FROM MessageSummaryCache c WHERE c.lastAccessedAt < :cutoffTime")
    int deleteByLastAccessedAtBefore(@Param("cutoffTime") Instant cutoffTime);
}
