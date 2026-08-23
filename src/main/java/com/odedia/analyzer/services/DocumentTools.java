package com.odedia.analyzer.services;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.odedia.analyzer.dto.DocumentInfo;
import com.odedia.analyzer.query.QueryTurnContext;

/**
 * Tools the chat model (and the MCP server) can call. Retrieval and collection-level
 * lookups are both tools so the model decides which to use per question.
 */
@Service
public class DocumentTools {

    private static final Pattern SOURCE_TAG = Pattern.compile(
            "\\[SOURCE:\\s*([^,\\]]+),\\s*PAGE:\\s*(\\d+)\\]", Pattern.CASE_INSENSITIVE);

    private final DocumentRepository documentRepo;
    private final VectorStore vectorStore;
    private final QueryRewriterService queryRewriter;

    public DocumentTools(DocumentRepository documentRepo, VectorStore vectorStore,
            QueryRewriterService queryRewriter) {
        this.documentRepo = documentRepo;
        this.vectorStore = vectorStore;
        this.queryRewriter = queryRewriter;
    }

    @Tool(description = "Search ALL uploaded documents for content relevant to a query and return "
            + "matching excerpts, each prefixed with [SOURCE: filename, PAGE: N]. ALWAYS call this "
            + "for questions about what the documents say, unless the user names a specific file "
            + "(then use searchInDocument) or a specific page (then use getPage). Pass a focused "
            + "search query; you may rephrase or translate it to better match the documents.")
    public String searchDocuments(String query, ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> ctx = context(toolContext);
        String conversationId = str(ctx.get("conversationId"));
        QueryTurnContext turn = QueryTurnContext.get(conversationId);
        if (turn != null) {
            turn.noteTool("searchDocuments");
        }
        try {
            String searchQuery = maybeRewrite(query, ctx, turn);
            int topK = toInt(ctx.get("topK"), 10);
            double threshold = toDouble(ctx.get("threshold"), 0.0);
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(searchQuery)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .build());
            return formatHits(docs, conversationId);
        }
        finally {
            if (turn != null) {
                turn.emitToolEnd("searchDocuments", System.currentTimeMillis() - t0);
            }
        }
    }

    @Tool(description = "Search inside ONE uploaded document by filename for content relevant to a "
            + "query. Use when the user names a specific file (e.g. 'only in terms.pdf'). The filename "
            + "must match a name from listDocuments. Returns excerpts prefixed with [SOURCE: filename, PAGE: N].")
    public String searchInDocument(String filename, String query, ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> ctx = context(toolContext);
        String conversationId = str(ctx.get("conversationId"));
        QueryTurnContext turn = QueryTurnContext.get(conversationId);
        if (turn != null) {
            turn.noteTool("searchInDocument");
        }
        try {
            if (filename == null || filename.isBlank()) {
                return "A filename is required. Call listDocuments to see uploaded names.";
            }
            String searchQuery = maybeRewrite(query, ctx, turn);
            int topK = toInt(ctx.get("topK"), 10);
            double threshold = toDouble(ctx.get("threshold"), 0.0);
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(searchQuery)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression("filename == " + quoteFilter(filename.trim()))
                    .build());
            return formatHits(docs, conversationId);
        }
        finally {
            if (turn != null) {
                turn.emitToolEnd("searchInDocument", System.currentTimeMillis() - t0);
            }
        }
    }

    @Tool(description = "Return the text of a specific page in a named uploaded document. Use when the "
            + "user asks what page N of a file says. Filename must match listDocuments. Page numbers "
            + "are 1-based as shown in [SOURCE: ...] tags.")
    public String getPage(String filename, int page, ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> ctx = context(toolContext);
        String conversationId = str(ctx.get("conversationId"));
        QueryTurnContext turn = QueryTurnContext.get(conversationId);
        if (turn != null) {
            turn.noteTool("getPage");
        }
        try {
            if (filename == null || filename.isBlank()) {
                return "A filename is required. Call listDocuments to see uploaded names.";
            }
            if (page < 1) {
                return "Page numbers start at 1.";
            }
            List<String> chunks = documentRepo.findChunkTexts(filename.trim(), page);
            if (chunks == null || chunks.isEmpty()) {
                return "No content was found for '" + filename.trim() + "' page " + page
                        + ". Call listDocuments to verify the filename and page count.";
            }
            String joined = String.join("\n\n---\n\n", chunks);
            collectCitations(joined, conversationId);
            return joined;
        }
        finally {
            if (turn != null) {
                turn.emitToolEnd("getPage", System.currentTimeMillis() - t0);
            }
        }
    }

    @Tool(description = "Returns collection-level statistics about ALL uploaded documents: how many "
            + "files, total PDF pages, and a breakdown by language. Use for questions about totals "
            + "across the whole collection (e.g. 'how many documents'). For how many pages ONE named "
            + "file has, use listDocuments instead — do not use this total as that file's page count.")
    public String documentStats(ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        String conversationId = str(context(toolContext).get("conversationId"));
        QueryTurnContext turn = QueryTurnContext.get(conversationId);
        if (turn != null) {
            turn.noteTool("documentStats");
        }
        try {
            List<DocumentInfo> docs = documentRepo.findDistinctDocuments();
            if (docs.isEmpty()) {
                return "There are no uploaded documents.";
            }
            int totalPages = docs.stream().mapToInt(DocumentInfo::getPages).sum();
            Map<String, Integer> byLanguage = new TreeMap<>();
            for (DocumentInfo d : docs) {
                byLanguage.merge(d.getLanguage() == null ? "unknown" : d.getLanguage(), 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Total documents: ").append(docs.size()).append('\n');
            sb.append("Total PDF pages (sum of each file's page count): ").append(totalPages).append('\n');
            sb.append("Documents by language:");
            byLanguage.forEach((lang, n) -> sb.append("\n  - ").append(lang).append(": ").append(n));
            return sb.toString();
        }
        finally {
            if (turn != null) {
                turn.emitToolEnd("documentStats", System.currentTimeMillis() - t0);
            }
        }
    }

    @Tool(description = "Lists EVERY uploaded document with its language and PDF page count. "
            + "Use for 'what documents are there', filenames, or how many pages a specific file has. "
            + "The page count is the number of PDF pages — not search hits and not how the file was chunked. "
            + "Use this before searchInDocument or getPage to get exact filenames.")
    public String listDocuments(ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        String conversationId = str(context(toolContext).get("conversationId"));
        QueryTurnContext turn = QueryTurnContext.get(conversationId);
        if (turn != null) {
            turn.noteTool("listDocuments");
        }
        try {
            List<DocumentInfo> docs = documentRepo.findDistinctDocuments();
            if (docs.isEmpty()) {
                return "There are no uploaded documents.";
            }
            StringBuilder sb = new StringBuilder(
                    "Uploaded documents (").append(docs.size())
                    .append("). Page count is the number of PDF pages in that file:\n");
            int i = 1;
            for (DocumentInfo d : docs) {
                sb.append(i++).append(". ").append(d.getFilename())
                        .append(" — language: ").append(d.getLanguage())
                        .append(", page count: ").append(d.getPages()).append('\n');
            }
            return sb.toString().trim();
        }
        finally {
            if (turn != null) {
                turn.emitToolEnd("listDocuments", System.currentTimeMillis() - t0);
            }
        }
    }

    private String maybeRewrite(String query, Map<String, Object> ctx, QueryTurnContext turn) {
        boolean rewrite = turn != null ? turn.isRewrite() : bool(ctx.get("rewrite"), false);
        if (!rewrite || query == null || query.isBlank()) {
            return query;
        }
        if (!queryRewriter.shouldRewrite(query) && !(turn != null && turn.isCrossLingual())) {
            return query;
        }
        String language = turn != null ? turn.getLanguage() : str(ctx.get("language"));
        String model = turn != null ? turn.getModelName() : str(ctx.get("model"));
        boolean cross = turn != null ? turn.isCrossLingual() : bool(ctx.get("crossLingual"), false);
        var history = turn != null ? turn.getHistory() : List.<org.springframework.ai.chat.messages.Message>of();
        return queryRewriter.rewriteQuery(query, history, language, model, cross);
    }

    private String formatHits(List<Document> docs, String conversationId) {
        if (docs == null || docs.isEmpty()) {
            return "No relevant content was found in the uploaded documents for that query.";
        }
        String joined = docs.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n"));
        collectCitations(joined, conversationId);
        return joined;
    }

    private static void collectCitations(String text, String conversationId) {
        QueryTurnContext turn = QueryTurnContext.get(conversationId);
        if (turn == null || text == null) {
            return;
        }
        Matcher m = SOURCE_TAG.matcher(text);
        while (m.find()) {
            try {
                turn.addCitation(m.group(1).trim(), Integer.parseInt(m.group(2)));
            }
            catch (NumberFormatException ignored) {
                // skip malformed tags
            }
        }
    }

    private static Map<String, Object> context(ToolContext toolContext) {
        return toolContext == null ? Map.of() : toolContext.getContext();
    }

    static String quoteFilter(String filename) {
        return "'" + filename.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean bool(Object v, boolean dflt) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return dflt;
        }
        return Boolean.parseBoolean(v.toString());
    }

    private static int toInt(Object v, int dflt) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? dflt : Integer.parseInt(v.toString());
        }
        catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static double toDouble(Object v, double dflt) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? dflt : Double.parseDouble(v.toString());
        }
        catch (NumberFormatException e) {
            return dflt;
        }
    }
}
