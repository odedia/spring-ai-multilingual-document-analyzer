package com.odedia.analyzer.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import com.odedia.analyzer.services.MessageSummarizationService;

import java.util.ArrayList;
import java.util.List;

/**
 * A ChatMemory implementation that uses token-based windowing with automatic
 * summarization of older messages to preserve context while managing token limits.
 *
 * Strategy:
 * 1. Maintains a token budget for conversation history
 * 2. Keeps recent messages in full
 * 3. Summarizes older messages when token limit is approached
 * 4. Stores summaries as system messages for context continuity
 */
public class SummarizingTokenWindowChatMemory implements ChatMemory {

    private static final Logger logger = LoggerFactory.getLogger(SummarizingTokenWindowChatMemory.class);

    private final ChatMemoryRepository chatMemoryRepository;
    private final MessageSummarizationService summarizationService;
    private final int maxTokens;
    private final int recentMessageCount;
    private final int approximateTokensPerChar = 4; // Conservative estimate: 1 token ≈ 4 characters

    /**
     * Creates a new SummarizingTokenWindowChatMemory.
     *
     * @param chatMemoryRepository The repository for persisting messages
     * @param summarizationService Service for creating message summaries
     * @param maxTokens Maximum tokens to maintain in memory window
     * @param recentMessageCount Number of recent messages to always keep in full (not summarized)
     */
    public SummarizingTokenWindowChatMemory(
            ChatMemoryRepository chatMemoryRepository,
            MessageSummarizationService summarizationService,
            int maxTokens,
            int recentMessageCount) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.summarizationService = summarizationService;
        this.maxTokens = maxTokens;
        this.recentMessageCount = recentMessageCount;

        logger.info("Initialized SummarizingTokenWindowChatMemory with maxTokens={}, recentMessageCount={}",
                maxTokens, recentMessageCount);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        // Get existing messages and append new ones
        List<Message> existingMessages = chatMemoryRepository.findByConversationId(conversationId);
        List<Message> allMessages = new ArrayList<>(existingMessages);
        allMessages.addAll(messages);
        chatMemoryRepository.saveAll(conversationId, allMessages);
        logger.debug("Added {} messages to conversation {}", messages.size(), conversationId);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> allMessages = chatMemoryRepository.findByConversationId(conversationId);

        if (allMessages.isEmpty()) {
            return new ArrayList<>();
        }

        // Apply token-based windowing with summarization
        return applyTokenWindowWithSummarization(conversationId, allMessages);
    }

    @Override
    public void clear(String conversationId) {
        chatMemoryRepository.deleteByConversationId(conversationId);
        logger.info("Cleared conversation {}", conversationId);
    }

    /**
     * Applies token-based windowing with automatic summarization of older messages.
     * Uses caching to avoid re-summarizing the same message ranges.
     *
     * Algorithm:
     * 1. Calculate total tokens in all messages
     * 2. If within limit, return all messages
     * 3. If over limit:
     *    a. Keep the most recent N messages in full
     *    b. Summarize older messages into a single context message (with caching)
     *    c. Return [summary] + [recent messages]
     */
    private List<Message> applyTokenWindowWithSummarization(String conversationId, List<Message> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        int totalTokens = estimateTokenCount(messages);
        logger.debug("Total tokens in conversation: {} (limit: {})", totalTokens, maxTokens);

        // If within token limit, return all messages
        if (totalTokens <= maxTokens) {
            logger.debug("Within token limit, returning all {} messages", messages.size());
            return messages;
        }

        // Need to apply summarization
        logger.info("Token limit exceeded ({} > {}), applying summarization", totalTokens, maxTokens);

        // Split messages into older (to summarize) and recent (to keep)
        if (messages.size() <= recentMessageCount) {
            // All messages are "recent", just truncate to fit tokens
            return truncateToTokenLimit(messages);
        }

        int splitPoint = messages.size() - recentMessageCount;
        List<Message> olderMessages = messages.subList(0, splitPoint);
        List<Message> recentMessages = messages.subList(splitPoint, messages.size());

        // Create summary of older messages (with caching)
        SystemMessage summary = summarizationService.summarizeMessages(conversationId, olderMessages);

        // Build result: [summary] + [recent messages]
        List<Message> result = new ArrayList<>();
        result.add(summary);
        result.addAll(recentMessages);

        int resultTokens = estimateTokenCount(result);
        logger.info("Summarized {} older messages. Result: {} messages, ~{} tokens",
                olderMessages.size(), result.size(), resultTokens);

        // If still over limit (unlikely), truncate recent messages
        if (resultTokens > maxTokens) {
            logger.warn("Still over token limit after summarization, truncating recent messages");
            return truncateToTokenLimit(result);
        }

        return result;
    }

    /**
     * Estimates token count for a list of messages.
     * Uses a conservative approximation: 1 token ≈ 4 characters.
     */
    private int estimateTokenCount(List<Message> messages) {
        int totalChars = messages.stream()
                .map(Message::getText)
                .mapToInt(content -> content != null ? content.length() : 0)
                .sum();

        return totalChars / approximateTokensPerChar;
    }

    /**
     * Truncates messages from the beginning to fit within token limit.
     * Keeps the most recent messages.
     */
    private List<Message> truncateToTokenLimit(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        int currentTokens = 0;

        // Iterate from most recent to oldest
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            int msgTokens = estimateTokenCount(List.of(msg));

            if (currentTokens + msgTokens > maxTokens) {
                break; // Would exceed limit
            }

            result.add(0, msg); // Add to beginning to maintain chronological order
            currentTokens += msgTokens;
        }

        logger.debug("Truncated to {} messages (~{} tokens)", result.size(), currentTokens);
        return result;
    }

    /**
     * Builder for SummarizingTokenWindowChatMemory.
     */
    public static class Builder {
        private ChatMemoryRepository chatMemoryRepository;
        private MessageSummarizationService summarizationService;
        private int maxTokens = 8000;
        private int recentMessageCount = 6; // Default: keep last 6 messages in full

        public Builder chatMemoryRepository(ChatMemoryRepository repository) {
            this.chatMemoryRepository = repository;
            return this;
        }

        public Builder summarizationService(MessageSummarizationService service) {
            this.summarizationService = service;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder recentMessageCount(int count) {
            this.recentMessageCount = count;
            return this;
        }

        public SummarizingTokenWindowChatMemory build() {
            if (chatMemoryRepository == null) {
                throw new IllegalStateException("ChatMemoryRepository is required");
            }
            if (summarizationService == null) {
                throw new IllegalStateException("MessageSummarizationService is required");
            }
            return new SummarizingTokenWindowChatMemory(
                    chatMemoryRepository,
                    summarizationService,
                    maxTokens,
                    recentMessageCount
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
