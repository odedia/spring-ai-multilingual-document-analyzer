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
import org.springframework.context.ApplicationEventPublisher;
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
 * Uses ResilientLlmService for retry logic on LLM calls.
 */
@Service
public class MessageSummarizationService {

    private static final Logger logger = LoggerFactory.getLogger(MessageSummarizationService.class);
    private static final int APPROXIMATE_TOKENS_PER_CHAR = 4;

    private final ChatModelRegistry chatModelRegistry;
    private final MessageSummaryCacheRepository cacheRepository;
    private final ResilientLlmService resilientLlm;
    private final ApplicationEventPublisher eventPublisher;

    public MessageSummarizationService(ChatModelRegistry chatModelRegistry,
            MessageSummaryCacheRepository cacheRepository,
            ResilientLlmService resilientLlm,
            ApplicationEventPublisher eventPublisher) {
        this.chatModelRegistry = chatModelRegistry;
        this.cacheRepository = cacheRepository;
        this.resilientLlm = resilientLlm;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Published while an actual (cache-miss) summarization LLM call is running,
     * so the UI can show a "summarizing earlier messages" indicator.
     * {@code active=true} on start, {@code active=false} when done.
     */
    public record SummarizationEvent(String conversationId, boolean active) {
    }

    /** A previously-cached summary covering the first {@code count} messages. */
    public record CachedPrefix(int count, String summaryText) {
    }

    /**
     * Returns the largest cached summary prefix covering at most {@code maxCount} messages,
     * WITHOUT generating anything. Lets the caller reuse an existing summary (and only
     * re-summarize when the recent tail has actually grown past the budget).
     */
    public java.util.Optional<CachedPrefix> getCachedPrefix(String conversationId, int maxCount) {
        return cacheRepository
                .findTopByConversationIdAndMessageCountLessThanEqualOrderByMessageCountDesc(conversationId, maxCount)
                .map(c -> new CachedPrefix(c.getMessageCount(), c.getSummaryText()));
    }

    /**
     * Produces a condensed summary of older messages for a conversation.
     *
     * Hallucination-hardening (H1/M5): summarization is INCREMENTAL and BOUNDED.
     * Instead of re-summarizing the whole (ever-growing) history in one call — which
     * eventually overflows the model and falls back to a useless stub — we fold only
     * the *new* older messages onto the best cached prefix summary, in batches whose
     * transcript never exceeds {@code batchTokens}. Each LLM call is therefore bounded
     * regardless of how long the conversation gets, and the running summary carries
     * information forward rather than crushing hundreds of messages into one pass.
     *
     * @param conversationId conversation id (for cache key)
     * @param messages       the older messages to summarize (chronological)
     * @param modelName      the model selected for THIS request (M4: keep summary
     *                       quality/language consistent with the answering model)
     * @param batchTokens    max transcript tokens folded per LLM call
     */
    @Transactional
    public SystemMessage summarizeMessages(String conversationId, List<Message> messages,
            String modelName, int batchTokens) {
        if (messages == null || messages.isEmpty()) {
            return new SystemMessage("No previous conversation context.");
        }
        int total = messages.size();
        String fullHash = generateMessageRangeHash(messages);

        // Exact hit for this full range → reuse.
        Optional<MessageSummaryCache> exact = cacheRepository
                .findByConversationIdAndMessageRangeHash(conversationId, fullHash);
        if (exact.isPresent()) {
            MessageSummaryCache c = exact.get();
            c.updateLastAccessed();
            cacheRepository.save(c);
            logger.info("Summary cache HIT for {} messages", total);
            return new SystemMessage("Previous conversation summary: " + c.getSummaryText());
        }

        // Incremental: fold new messages onto the largest cached prefix (≤ total).
        Optional<MessageSummaryCache> prefix = cacheRepository
                .findTopByConversationIdAndMessageCountLessThanEqualOrderByMessageCountDesc(conversationId, total);
        String running = null;
        int startIdx = 0;
        if (prefix.isPresent() && prefix.get().getMessageCount() < total) {
            running = prefix.get().getSummaryText();
            startIdx = prefix.get().getMessageCount();
        }
        List<Message> toFold = messages.subList(startIdx, total);
        if (toFold.isEmpty()) {
            return new SystemMessage("Previous conversation summary: " + (running == null ? "" : running));
        }

        logger.info("Summarizing: folding {} new message(s) onto prefix of {} (total {})",
                toFold.size(), startIdx, total);

        eventPublisher.publishEvent(new SummarizationEvent(conversationId, true));
        try {
            for (List<Message> batch : batchByTokens(toFold, batchTokens)) {
                running = foldSummary(running, batch, modelName, batchTokens);
            }
        } finally {
            eventPublisher.publishEvent(new SummarizationEvent(conversationId, false));
        }

        int estTokens = estimateTokens(running);
        cacheRepository.save(new MessageSummaryCache(conversationId, fullHash, running, total, estTokens));
        logger.info("Summary updated through {} messages (~{} tokens)", total, estTokens);
        return new SystemMessage("Previous conversation summary: " + running);
    }

    /** Splits messages into batches whose transcript stays within {@code batchTokens}. */
    private List<List<Message>> batchByTokens(List<Message> messages, int batchTokens) {
        List<List<Message>> batches = new ArrayList<>();
        List<Message> cur = new ArrayList<>();
        int curTokens = 0;
        for (Message m : messages) {
            int t = estimateTokens(m.getText());
            if (!cur.isEmpty() && curTokens + t > batchTokens) {
                batches.add(cur);
                cur = new ArrayList<>();
                curTokens = 0;
            }
            cur.add(m);
            curTokens += t;
        }
        if (!cur.isEmpty()) {
            batches.add(cur);
        }
        return batches;
    }

    /** Folds one bounded batch of messages into the running summary via the selected model. */
    private String foldSummary(String existingSummary, List<Message> batch, String modelName, int batchTokens) {
        int budgetChars = Math.max(2000, batchTokens * 4); // hard cap, also guards a single giant message
        StringBuilder transcript = new StringBuilder();
        for (Message m : batch) {
            String content = m.getText();
            if (content == null) {
                continue;
            }
            transcript.append(determineRole(m)).append(": ").append(content).append("\n\n");
            if (transcript.length() > budgetChars) {
                transcript.setLength(budgetChars);
                transcript.append(" …[truncated]");
                break;
            }
        }

        boolean hasExisting = existingSummary != null && !existingSummary.isBlank();
        String prompt = hasExisting
                ? """
                        You maintain a running summary of a long conversation. Update it to fold in the new turns.
                        Keep it a concise, coherent narrative (~250-400 tokens). Preserve key facts, decisions,
                        numbers, names, and user preferences. Do NOT invent anything not present below.

                        Existing summary:
                        ---
                        %s
                        ---

                        New conversation turns:
                        ---
                        %s
                        ---

                        Updated summary:
                        """.formatted(existingSummary, transcript)
                : """
                        Create a concise summary (~250-400 tokens) of the following conversation turns.
                        Preserve key facts, decisions, numbers, names, and user preferences. Coherent
                        narrative, not bullet points. Do NOT invent anything not present below.

                        Conversation turns:
                        ---
                        %s
                        ---

                        Summary:
                        """.formatted(transcript);

        String fallback = hasExisting ? existingSummary
                : "Earlier conversation covered " + batch.size() + " message(s).";
        ChatClient client = chatModelRegistry.clientFor(modelName);
        return resilientLlm.callWithRetry(
                "MessageSummarization",
                () -> client.prompt().user(prompt).call().content(),
                fallback);
    }

    /** Conservative token estimate (slightly over-counts to stay within bounds). */
    private int estimateTokens(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / 3.5);
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
     * Recent messages are kept intact, older messages are grouped for
     * summarization.
     *
     * @param messages           All messages in chronological order
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
