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

    private final ChatModelRegistry chatModelRegistry;

    public QueryRewriterService(ChatModelRegistry chatModelRegistry) {
        this.chatModelRegistry = chatModelRegistry;
    }

    /**
     * Rewrites a user query to be more search-effective.
     *
     * @param originalQuery The user's original question
     * @param conversationHistory Recent messages for context
     * @param language The language to respond in ("he" or "en")
     * @param modelName The model selected for this request (keeps rewrite consistent)
     * @return Rewritten query optimized for vector search
     */
    public String rewriteQuery(String originalQuery, List<Message> conversationHistory, String language,
            String modelName) {
        return rewriteQuery(originalQuery, conversationHistory, language, modelName, false);
    }

    /**
     * @param crossLingual when true, the rewritten search query also includes English/Latin
     *                     equivalents and transliteration normalizations (e.g. "ספרינג" → also
     *                     "Spring") so it can match documents written in another language.
     */
    public String rewriteQuery(String originalQuery, List<Message> conversationHistory, String language,
            String modelName, boolean crossLingual) {
        // Build conversation context from recent history
        String conversationContext = conversationHistory.stream()
                .limit(4)  // Last 2 exchanges (user + assistant)
                .map(Message::getText)
                .collect(Collectors.joining("\n"));

        boolean isHebrew = "he".equals(language);

        String rewritePrompt = crossLingual
                ? String.format("""
                    You optimize a user's question into a SEARCH query for a multilingual document store.
                    The documents may be in a DIFFERENT language than the question.

                    Conversation history:
                    ---
                    %s
                    ---

                    User's current question: "%s"

                    Produce a single search query that:
                    1. Resolves pronouns/references ("it", "that", "זה") from the conversation history.
                    2. If the current question names a DIFFERENT document, person, or topic than the previous turn, search ONLY for that new subject. Do NOT copy previous filenames, page numbers, or topics into the query.
                    3. Keeps the original key terms AND adds their English/Latin equivalents.
                    4. Normalizes transliterated names to their canonical form (e.g. "ספרינג" -> also "Spring", "ריאקט" -> also "React").
                    5. Includes a few closely-related terms likely to appear in the documents.

                    Return ONLY the search query as a single line mixing both languages where useful.
                    No explanations, no quotes.
                    """,
                    conversationContext.isEmpty() ? "No previous conversation" : conversationContext,
                    originalQuery)
                : String.format("""
                    You are a search query optimizer. Your task is to rewrite the user's question
                    to make it more effective for semantic vector search.

                    Conversation history:
                    ---
                    %s
                    ---

                    User's current question: "%s"

                    Rewrite this question to:
                    1. Resolve pronouns/references ("it", "that", "זה") from the conversation history when the current question does not name a new subject.
                    2. If the current question names a DIFFERENT document, person, or topic than the previous turn, rewrite ONLY about that new subject. Do NOT copy previous filenames, page numbers, or topics into the query.
                    3. Expand any abbreviations or acronyms
                    4. Add related technical terms that might appear in documentation
                    5. Make vague references specific (e.g., "it" → "the authentication system")
                    6. Preserve the original intent and meaning

                    Important: Return ONLY the rewritten question in %s.
                    Do not add explanations or meta-commentary.
                    """,
                    conversationContext.isEmpty() ? "No previous conversation" : conversationContext,
                    originalQuery,
                    isHebrew ? "Hebrew" : "English");

        try {
            String rewrittenQuery = chatModelRegistry.clientFor(modelName)
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
        if (query == null) {
            return false;
        }
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) {
            return false;
        }

        // Short fragments are usually context-dependent follow-ups ("and the deductible?").
        int words = q.split("\\s+").length;
        if (words <= 4) {
            return true;
        }

        // Pronoun / deictic references that only make sense with prior context (EN + HE).
        String padded = " " + q + " ";
        String[] deictic = {
                " it ", " its ", " this ", " that ", " they ", " them ", " these ", " those ", " he ", " she ",
                " זה", " זו", " הם", " הן", " עליו", " עליה", " בו", " בה", " שלהם", " הללו", " כך"
        };
        for (String d : deictic) {
            if (padded.contains(d)) {
                return true;
            }
        }

        // Otherwise keep the user's original wording — rewriting a clear, self-contained
        // question only risks drifting the search toward the wrong documents (M3).
        return false;
    }
}
