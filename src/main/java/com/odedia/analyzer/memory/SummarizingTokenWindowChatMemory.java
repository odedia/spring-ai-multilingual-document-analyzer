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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A ChatMemory implementation that uses token-based windowing with automatic
 * summarization of older messages to preserve context while managing token
 * limits.
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
    private final int summarizeBatchTokens;

    /** Per-conversation token-budget overrides (set from the UI "Chat memory" selector). */
    private final Map<String, Integer> tokenLimitOverrides = new ConcurrentHashMap<>();

    /** Per-conversation model name for the current request (so summaries use the same model). */
    private final Map<String, String> modelByConversation = new ConcurrentHashMap<>();

    /** Per-conversation override of how many recent messages to keep verbatim. */
    private final Map<String, Integer> recentOverrides = new ConcurrentHashMap<>();

    /**
     * Creates a new SummarizingTokenWindowChatMemory.
     *
     * @param chatMemoryRepository The repository for persisting messages
     * @param summarizationService Service for creating message summaries
     * @param maxTokens            Maximum tokens to maintain in memory window
     * @param recentMessageCount   Number of recent messages to always keep in full
     *                             (not summarized)
     */
    public SummarizingTokenWindowChatMemory(
            ChatMemoryRepository chatMemoryRepository,
            MessageSummarizationService summarizationService,
            int maxTokens,
            int recentMessageCount,
            int summarizeBatchTokens) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.summarizationService = summarizationService;
        this.maxTokens = maxTokens;
        this.recentMessageCount = recentMessageCount;
        this.summarizeBatchTokens = summarizeBatchTokens;

        logger.info("Initialized SummarizingTokenWindowChatMemory with maxTokens={}, recentMessageCount={}, summarizeBatchTokens={}",
                maxTokens, recentMessageCount, summarizeBatchTokens);
    }

    /**
     * Sets the chat-history token budget for a single conversation, overriding the
     * configured default. A null/non-positive value clears the override.
     */
    public void setMaxTokensFor(String conversationId, Integer tokens) {
        if (conversationId == null) {
            return;
        }
        if (tokens == null || tokens <= 0) {
            tokenLimitOverrides.remove(conversationId);
        } else {
            tokenLimitOverrides.put(conversationId, tokens);
        }
    }

    /** Records the model selected for the current request so summaries use the same model. */
    public void setModelFor(String conversationId, String modelName) {
        if (conversationId != null && modelName != null) {
            modelByConversation.put(conversationId, modelName);
        }
    }

    /** Overrides how many recent messages are kept verbatim for one conversation. */
    public void setRecentFor(String conversationId, Integer count) {
        if (conversationId == null) {
            return;
        }
        if (count == null || count <= 0) {
            recentOverrides.remove(conversationId);
        } else {
            recentOverrides.put(conversationId, count);
        }
    }

    private int recentFor(String conversationId) {
        return recentOverrides.getOrDefault(conversationId, recentMessageCount);
    }

    /** Forgets per-conversation overrides (call when a conversation is deleted). */
    public void forget(String conversationId) {
        tokenLimitOverrides.remove(conversationId);
        modelByConversation.remove(conversationId);
        recentOverrides.remove(conversationId);
    }

    private int limitFor(String conversationId) {
        return tokenLimitOverrides.getOrDefault(conversationId, maxTokens);
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
     * Applies token-based windowing with summarization of older messages, with HYSTERESIS:
     * once we summarize, we keep reusing that summary + a growing tail of recent messages
     * (no new LLM call) until the tail itself overflows the budget. Only then do we
     * re-summarize, compressing aggressively so there's headroom (~half the budget) to fill
     * up again. This avoids the previous behaviour of summarizing on every single turn.
     */
    private List<Message> applyTokenWindowWithSummarization(String conversationId, List<Message> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        int maxTokens = limitFor(conversationId);

        // Under the limit → send everything verbatim.
        if (estimateTokenCount(messages) <= maxTokens) {
            return messages;
        }

        int recent = recentFor(conversationId);
        if (messages.size() <= recent) {
            return truncateToTokenLimit(messages, maxTokens);
        }

        // 1) Try to REUSE an existing summary prefix without summarizing again.
        var cached = summarizationService.getCachedPrefix(conversationId, messages.size());
        if (cached.isPresent() && cached.get().count() > 0 && cached.get().count() < messages.size()) {
            int s = cached.get().count();
            List<Message> reuse = new ArrayList<>();
            reuse.add(new SystemMessage("Previous conversation summary: " + cached.get().summaryText()));
            reuse.addAll(messages.subList(s, messages.size()));
            if (estimateTokenCount(reuse) <= maxTokens) {
                logger.debug("Reusing cached summary (prefix={}); no new summarization", s);
                return reuse; // hysteresis: still fits, don't summarize
            }
        }

        // 2) Tail has overflowed → advance the boundary and (re)summarize. Compress so the
        //    kept tail is ~half the budget, leaving room to grow before the next summarization.
        int splitPoint = chooseBoundary(messages, maxTokens);
        String modelName = modelByConversation.get(conversationId);
        SystemMessage summary = summarizationService.summarizeMessages(
                conversationId, messages.subList(0, splitPoint), modelName, summarizeBatchTokens);

        List<Message> result = new ArrayList<>();
        result.add(summary);
        result.addAll(messages.subList(splitPoint, messages.size()));

        int resultTokens = estimateTokenCount(result);
        logger.info("Re-summarized through message {} of {}; result ~{} tokens (limit {})",
                splitPoint, messages.size(), resultTokens, maxTokens);

        if (resultTokens > maxTokens) {
            logger.warn("Still over limit after summarization, truncating recent messages");
            return truncateToTokenLimit(result, maxTokens);
        }
        return result;
    }

    /**
     * Picks how many of the oldest messages to fold into the summary so the kept tail is about
     * half the budget (headroom for the conversation to grow before the next summarization).
     * Always keeps at least the 2 most recent messages.
     */
    private int chooseBoundary(List<Message> messages, int maxTokens) {
        int targetTail = Math.max(maxTokens / 2, 1000);
        int tail = 0;
        int i = messages.size() - 1;
        for (; i >= 0; i--) {
            int t = estimateTokensForText(messages.get(i).getText());
            int keptSoFar = messages.size() - 1 - i;
            if (keptSoFar >= 2 && tail + t > targetTail) {
                break;
            }
            tail += t;
        }
        int split = i + 1; // messages[0..split) get summarized
        split = Math.min(split, messages.size() - 2); // keep >= 2 recent
        return Math.max(split, 1);
    }

    /**
     * Estimates token count for a list of messages.
     * Uses language-aware approximation:
     * - English: 1 token ≈ 4 characters
     * - Hebrew: 1 token ≈ 2-3 characters (Hebrew tokens are denser)
     */
    private int estimateTokenCount(List<Message> messages) {
        int totalTokens = 0;
        for (Message message : messages) {
            String content = message.getText();
            if (content != null) {
                totalTokens += estimateTokensForText(content);
            }
        }
        return totalTokens;
    }

    /**
     * Estimate tokens for a single text, using language-aware heuristics.
     */
    private int estimateTokensForText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // Count Hebrew characters to determine language mix
        long hebrewChars = text.chars()
                .filter(c -> (c >= 0x0590 && c <= 0x05FF)) // Hebrew block
                .count();

        double hebrewRatio = (double) hebrewChars / text.length();

        // Deliberately conservative (over-counts tokens) so we summarize/trim a little
        // early rather than overflow the model window. Hebrew BPE is token-dense, so it
        // gets the lower chars/token figure.
        double avgCharsPerToken = (hebrewRatio * 2.0) + ((1 - hebrewRatio) * 3.5);

        return (int) Math.ceil(text.length() / avgCharsPerToken);
    }

    /**
     * Truncates messages from the beginning to fit within token limit.
     * Keeps the most recent messages.
     */
    private List<Message> truncateToTokenLimit(List<Message> messages, int maxTokens) {
        List<Message> result = new ArrayList<>();
        int currentTokens = 0;
        int kept = 0;

        // Iterate from most recent to oldest. Always keep at least the most recent
        // message (even if it alone exceeds the budget) so we never return empty
        // history — empty history is what makes the model lose the thread and improvise.
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            int msgTokens = estimateTokensForText(msg.getText());

            if (!result.isEmpty() && currentTokens + msgTokens > maxTokens) {
                break; // Would exceed limit (but we already kept the most recent)
            }

            result.add(0, msg); // Add to beginning to maintain chronological order
            currentTokens += msgTokens;
            kept++;
        }

        // Make the loss explicit rather than silently dropping context.
        if (kept < messages.size()) {
            result.add(0, new SystemMessage("[Earlier messages were omitted to fit the context window.]"));
        }

        logger.debug("Truncated to {} of {} messages (~{} tokens)", kept, messages.size(), currentTokens);
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
        private int summarizeBatchTokens = 6000;

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

        public Builder summarizeBatchTokens(int tokens) {
            this.summarizeBatchTokens = tokens;
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
                    recentMessageCount,
                    summarizeBatchTokens);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
