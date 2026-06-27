package com.odedia.analyzer.services;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.odedia.analyzer.dto.DocumentInfo;

/**
 * Tools the chat model can call. Retrieval and collection-level lookups are BOTH tools, so
 * the model decides which to use per question instead of us routing by keywords:
 * - searchDocuments: semantic search over document content (RAG).
 * - documentStats / listDocuments: facts about the whole collection (counts, names, totals).
 */
@Service
public class DocumentTools {

    private final DocumentRepository documentRepo;
    private final VectorStore vectorStore;

    public DocumentTools(DocumentRepository documentRepo, VectorStore vectorStore) {
        this.documentRepo = documentRepo;
        this.vectorStore = vectorStore;
    }

    @Tool(description = "Search the uploaded documents for content relevant to a query and return the "
            + "matching excerpts, each prefixed with its [SOURCE: filename, PAGE: N] tag. ALWAYS call this "
            + "for any question about what the documents say or contain. Pass a focused search query; you "
            + "may rephrase or translate it (e.g. to English) to better match the documents.")
    public String searchDocuments(String query, ToolContext toolContext) {
        Map<String, Object> ctx = toolContext == null ? Map.of() : toolContext.getContext();
        int topK = toInt(ctx.get("topK"), 10);
        double threshold = toDouble(ctx.get("threshold"), 0.0);

        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .build());

        if (docs == null || docs.isEmpty()) {
            return "No relevant content was found in the uploaded documents for that query.";
        }
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool(description = "Returns collection-level statistics about ALL uploaded documents: total number "
            + "of documents, total pages, total segments, and a breakdown by language. Use for questions "
            + "about totals or counts across the whole collection (e.g. 'how many documents', 'how many pages').")
    public String documentStats() {
        List<DocumentInfo> docs = documentRepo.findDistinctDocuments();
        if (docs.isEmpty()) {
            return "There are no uploaded documents.";
        }
        int totalPages = docs.stream().mapToInt(DocumentInfo::getPages).sum();
        int totalChunks = docs.stream().mapToInt(DocumentInfo::getChunks).sum();
        Map<String, Integer> byLanguage = new TreeMap<>();
        for (DocumentInfo d : docs) {
            byLanguage.merge(d.getLanguage() == null ? "unknown" : d.getLanguage(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Total documents: ").append(docs.size()).append('\n');
        sb.append("Total pages: ").append(totalPages).append('\n');
        sb.append("Total segments: ").append(totalChunks).append('\n');
        sb.append("Documents by language:");
        byLanguage.forEach((lang, n) -> sb.append("\n  - ").append(lang).append(": ").append(n));
        return sb.toString();
    }

    @Tool(description = "Lists EVERY uploaded document with its language, page count and segment count. "
            + "Use for questions like 'what documents are there', 'what are the document names', "
            + "'list the files', or when asked to summarize or describe the whole collection.")
    public String listDocuments() {
        List<DocumentInfo> docs = documentRepo.findDistinctDocuments();
        if (docs.isEmpty()) {
            return "There are no uploaded documents.";
        }
        StringBuilder sb = new StringBuilder("Uploaded documents (").append(docs.size()).append("):");
        int i = 1;
        for (DocumentInfo d : docs) {
            sb.append('\n').append(i++).append(". ").append(d.getFilename())
                    .append(" — language: ").append(d.getLanguage())
                    .append(", pages: ").append(d.getPages())
                    .append(", segments: ").append(d.getChunks());
        }
        return sb.toString();
    }

    private static int toInt(Object v, int dflt) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? dflt : Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static double toDouble(Object v, double dflt) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? dflt : Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
