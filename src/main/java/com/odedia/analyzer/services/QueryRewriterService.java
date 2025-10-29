package com.odedia.analyzer.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for rewriting user queries to improve vector search effectiveness.
 *
 * Query rewriting transforms vague or poorly-worded questions into more specific,
 * search-optimized queries by:
 * - Adding context from conversation history
 * - Expanding abbreviations and acronyms
 * - Adding relevant technical terms
 * - Clarifying ambiguous references
 */
@Service
public class QueryRewriterService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriterService.class);

    private final ChatClient chatClient;

    public QueryRewriterService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Rewrites a user query to be more search-effective.
     *
     * @param originalQuery The user's original question
     * @param conversationHistory Recent messages for context
     * @param language The language to respond in ("he" or "en")
     * @return Rewritten query optimized for vector search
     */
    public String rewriteQuery(String originalQuery, List<Message> conversationHistory, String language) {
        // Build conversation context from recent history
        String conversationContext = conversationHistory.stream()
                .limit(4)  // Last 2 exchanges (user + assistant)
                .map(Message::getText)
                .collect(Collectors.joining("\n"));

        boolean isHebrew = "he".equals(language);

        String rewritePrompt = String.format("""
            You are a search query optimizer. Your task is to rewrite the user's question
            to make it more effective for semantic vector search.

            Conversation history:
            ---
            %s
            ---

            User's current question: "%s"

            Rewrite this question to:
            1. Include relevant context from the conversation history
            2. Expand any abbreviations or acronyms
            3. Add related technical terms that might appear in documentation
            4. Make vague references specific (e.g., "it" → "the authentication system")
            5. Preserve the original intent and meaning

            Important: Return ONLY the rewritten question in %s.
            Do not add explanations or meta-commentary.
            """,
            conversationContext.isEmpty() ? "No previous conversation" : conversationContext,
            originalQuery,
            isHebrew ? "Hebrew" : "English"
        );

        try {
            String rewrittenQuery = chatClient
                    .prompt()
                    .user(rewritePrompt)
                    .call()
                    .content()
                    .trim();

            logger.info("Query rewrite [{}]: '{}' → '{}'",
                       language, originalQuery, rewrittenQuery);

            return rewrittenQuery;

        } catch (Exception e) {
            logger.error("Query rewriting failed, using original query", e);
            return originalQuery;  // Fallback to original on error
        }
    }

    /**
     * Checks if a query would benefit from rewriting.
     * Simple factual queries often don't need rewriting.
     *
     * @param query The user's question
     * @return true if rewriting is recommended
     */
    public boolean shouldRewrite(String query) {
        if (query == null || query.length() < 10) {
            return false;  // Too short to benefit
        }

        String lower = query.toLowerCase();

        // Very simple questions don't need rewriting
        if (lower.matches("^what is .{1,20}\\??$") ||
            lower.matches("^define .{1,20}\\??$")) {
            return false;
        }

        // Questions with pronouns benefit from rewriting
        if (lower.contains(" it ") ||
            lower.contains(" this ") ||
            lower.contains(" that ") ||
            lower.contains(" they ")) {
            return true;
        }

        // Complex or multi-part questions benefit
        if (query.length() > 100 ||
            lower.contains(" and ") ||
            lower.contains(" or ")) {
            return true;
        }

        // Default: rewrite for better results
        return true;
    }
}
