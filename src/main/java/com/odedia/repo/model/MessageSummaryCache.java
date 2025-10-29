package com.odedia.repo.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entity that caches message summaries to avoid re-summarizing the same message ranges.
 *
 * Caching strategy:
 * - Key is based on conversation ID + hash of message IDs being summarized
 * - Stores the generated summary text
 * - Includes timestamp for potential cache expiration/cleanup
 */
@Entity
@Table(name = "message_summary_cache", indexes = {
    @Index(name = "idx_conversation_hash", columnList = "conversationId,messageRangeHash")
})
public class MessageSummaryCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * The conversation ID these messages belong to
     */
    @Column(nullable = false)
    private String conversationId;

    /**
     * Hash of the message IDs in this range (for cache key uniqueness)
     */
    @Column(nullable = false, length = 64)
    private String messageRangeHash;

    /**
     * The generated summary text
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    /**
     * When this summary was created
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * When this summary was last accessed (for LRU cleanup)
     */
    @Column(nullable = false)
    private Instant lastAccessedAt;

    /**
     * Number of messages that were summarized
     */
    @Column(nullable = false)
    private int messageCount;

    /**
     * Estimated token count of the summary
     */
    @Column(nullable = false)
    private int estimatedTokens;

    // Constructors
    public MessageSummaryCache() {
    }

    public MessageSummaryCache(String conversationId, String messageRangeHash, String summaryText,
                               int messageCount, int estimatedTokens) {
        this.conversationId = conversationId;
        this.messageRangeHash = messageRangeHash;
        this.summaryText = summaryText;
        this.messageCount = messageCount;
        this.estimatedTokens = estimatedTokens;
        this.createdAt = Instant.now();
        this.lastAccessedAt = Instant.now();
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getMessageRangeHash() {
        return messageRangeHash;
    }

    public void setMessageRangeHash(String messageRangeHash) {
        this.messageRangeHash = messageRangeHash;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    public void setEstimatedTokens(int estimatedTokens) {
        this.estimatedTokens = estimatedTokens;
    }

    /**
     * Updates the last accessed timestamp (for LRU tracking)
     */
    public void updateLastAccessed() {
        this.lastAccessedAt = Instant.now();
    }
}
