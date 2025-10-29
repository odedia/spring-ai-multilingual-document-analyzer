package com.odedia.analyzer.services;

import com.odedia.repo.jpa.MessageSummaryCacheRepository;
import com.odedia.repo.model.MessageSummaryCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service responsible for summarizing older chat messages to compress context
 * while preserving important information from the conversation history.
 * Includes intelligent caching to avoid re-summarizing the same message ranges.
 */
@Service
public class MessageSummarizationService {

    private static final Logger logger = LoggerFactory.getLogger(MessageSummarizationService.class);
    private static final int APPROXIMATE_TOKENS_PER_CHAR = 4;

    private final ChatClient chatClient;
    private final MessageSummaryCacheRepository cacheRepository;

    public MessageSummarizationService(ChatClient.Builder chatClientBuilder,
                                       MessageSummaryCacheRepository cacheRepository) {
        this.chatClient = chatClientBuilder.build();
        this.cacheRepository = cacheRepository;
    }

    /**
     * Summarizes a list of messages into a condensed context summary.
     * Uses caching to avoid re-summarizing the same message ranges.
     *
     * @param conversationId The conversation ID (for cache lookup)
     * @param messages The messages to summarize (should be chronological)
     * @return A system message containing the summary
     */
    @Transactional
    public SystemMessage summarizeMessages(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new SystemMessage("No previous conversation context.");
        }

        // Generate a hash of the messages for cache lookup
        String messageRangeHash = generateMessageRangeHash(messages);

        // Check cache first
        Optional<MessageSummaryCache> cachedSummary = cacheRepository
                .findByConversationIdAndMessageRangeHash(conversationId, messageRangeHash);

        if (cachedSummary.isPresent()) {
            MessageSummaryCache cache = cachedSummary.get();
            cache.updateLastAccessed();
            cacheRepository.save(cache);

            logger.info("Cache HIT: Retrieved summary for {} messages (hash: {})",
                    messages.size(), messageRangeHash.substring(0, 8));

            return new SystemMessage("Previous conversation summary: " + cache.getSummaryText());
        }

        // Cache miss - generate new summary
        logger.info("Cache MISS: Summarizing {} messages into condensed context", messages.size());

        // Build a conversation transcript for summarization
        StringBuilder transcript = new StringBuilder();
        for (Message msg : messages) {
            String role = determineRole(msg);
            String content = msg.getText();
            transcript.append(role).append(": ").append(content).append("\n\n");
        }

        // Create a prompt for the AI to summarize
        String summarizationPrompt = String.format("""
                Please create a concise summary of the following conversation history.
                Focus on:
                1. Key topics and questions discussed
                2. Important facts, decisions, or conclusions reached
                3. Any context that would be relevant for continuing the conversation
                4. User preferences or requirements mentioned

                Keep the summary brief but informative (aim for 200-300 tokens).
                Format it as a coherent narrative, not bullet points.

                Conversation to summarize:
                ---
                %s
                ---

                Summary:
                """, transcript.toString());

        try {
            String summary = chatClient
                    .prompt()
                    .user(summarizationPrompt)
                    .call()
                    .content();

            logger.info("Generated summary: {}", summary.substring(0, Math.min(100, summary.length())) + "...");

            // Store in cache
            int estimatedTokens = summary.length() / APPROXIMATE_TOKENS_PER_CHAR;
            MessageSummaryCache newCache = new MessageSummaryCache(
                    conversationId,
                    messageRangeHash,
                    summary,
                    messages.size(),
                    estimatedTokens
            );
            cacheRepository.save(newCache);

            logger.info("Cached summary (hash: {}, tokens: ~{})",
                    messageRangeHash.substring(0, 8), estimatedTokens);

            return new SystemMessage("Previous conversation summary: " + summary);

        } catch (Exception e) {
            logger.error("Failed to generate summary, using fallback", e);
            return new SystemMessage("Previous conversation covered " + messages.size() + " messages about various topics.");
        }
    }

    /**
     * Determines the role/type of a message for transcript generation.
     */
    private String determineRole(Message msg) {
        if (msg instanceof UserMessage) {
            return "User";
        } else if (msg instanceof AssistantMessage) {
            return "Assistant";
        } else if (msg instanceof SystemMessage) {
            return "System";
        } else {
            // Fallback for generic messages
            String msgType = msg.getMessageType() != null ? msg.getMessageType().toString() : "";
            if (msgType.toLowerCase().contains("user")) {
                return "User";
            } else if (msgType.toLowerCase().contains("assistant") || msgType.toLowerCase().contains("ai")) {
                return "Assistant";
            }
            return "Unknown";
        }
    }

    /**
     * Groups messages into batches for summarization.
     * Recent messages are kept intact, older messages are grouped for summarization.
     *
     * @param messages All messages in chronological order
     * @param recentMessageCount How many recent messages to keep unsummarized
     * @return List where older messages are grouped together
     */
    public List<List<Message>> batchMessagesForSummarization(List<Message> messages, int recentMessageCount) {
        List<List<Message>> batches = new ArrayList<>();

        if (messages.size() <= recentMessageCount) {
            // All messages are recent enough, no summarization needed
            return batches;
        }

        // Separate older messages from recent ones
        int splitPoint = messages.size() - recentMessageCount;
        List<Message> olderMessages = messages.subList(0, splitPoint);

        if (!olderMessages.isEmpty()) {
            batches.add(olderMessages);
        }

        return batches;
    }

    /**
     * Generates a unique hash for a range of messages.
     * The hash is based on the message content and order, ensuring that the same
     * sequence of messages always produces the same hash.
     *
     * @param messages The messages to hash
     * @return SHA-256 hash as hex string
     */
    private String generateMessageRangeHash(List<Message> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Hash each message's text in order
            for (Message msg : messages) {
                String text = msg.getText();
                if (text != null) {
                    digest.update(text.getBytes(StandardCharsets.UTF_8));
                }
                // Also include message type to ensure uniqueness
                String msgType = determineRole(msg);
                digest.update(msgType.getBytes(StandardCharsets.UTF_8));
            }

            byte[] hashBytes = digest.digest();

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 algorithm not available", e);
            // Fallback: simple hash based on message count and first/last message
            return String.valueOf(messages.size()) + "_" +
                    (messages.isEmpty() ? "empty" : messages.get(0).getText().hashCode());
        }
    }
}
