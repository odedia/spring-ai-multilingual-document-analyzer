package com.odedia.analyzer.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.odedia.repo.jpa.DocumentFigureRepository;
import com.odedia.repo.jpa.DocumentFigureView;

/**
 * Tools the chat model (and the MCP server) can call. Retrieval and collection-level
 * lookups are both tools so the model decides which to use per question.
 */
@Service
public class DocumentTools {

    private static final Pattern SOURCE_TAG = Pattern.compile(
            "\\[SOURCE:\\s*([^,\\]]+),\\s*PAGE:\\s*(\\d+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIGURE_ID = Pattern.compile(
            "\\[FIGURE:\\s*([0-9a-fA-F-]{36})\\]");
    private static final Pattern FIGURE_KIND = Pattern.compile(
            "\\[KIND:\\s*([^\\]]+)\\]");

    private final DocumentRepository documentRepo;
    private final VectorStore vectorStore;
    private final QueryRewriterService queryRewriter;
    private final DocumentFigureRepository figureRepo;

    public DocumentTools(DocumentRepository documentRepo, VectorStore vectorStore,
            QueryRewriterService queryRewriter, DocumentFigureRepository figureRepo) {
        this.documentRepo = documentRepo;
        this.vectorStore = vectorStore;
        this.queryRewriter = queryRewriter;
        this.figureRepo = figureRepo;
    }

    @Tool(description = "Search ALL uploaded documents for content relevant to a query and return "
            + "matching excerpts, each prefixed with [SOURCE: filename, PAGE: N]. ALWAYS call this "
            + "for questions about what the documents say, unless the user names a specific file "
            + "(then use searchInDocument) or a specific page (then use getPage). Pass a focused "
            + "search query; you may rephrase or translate it to better match the documents. "
            + "For estimates/interpolation from a graph, search for that quantity's graphs and axis "
            + "ranges (absorbance, mABS, time, figure) — the exact number often is not printed.")
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
            List<Document> docs = retrieveHits(searchQuery, topK, threshold, null);
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
            List<Document> docs = retrieveHits(searchQuery, topK, threshold, filename.trim());
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
            String imageNote = describeStoredImages(filename.trim(), page, conversationId, false);
            if ((chunks == null || chunks.isEmpty()) && imageNote.isEmpty()) {
                return "No content was found for '" + filename.trim() + "' page " + page
                        + ". Call listDocuments to verify the filename and page count.";
            }
            if (chunks == null || chunks.isEmpty()) {
                collectCitations("[SOURCE: " + filename.trim() + ", PAGE: " + page + "]", conversationId);
                return imageNote;
            }
            String joined = String.join("\n\n---\n\n", chunks);
            collectCitations(joined, conversationId);
            return imageNote.isEmpty() ? joined : joined + "\n\n" + imageNote;
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

    @Tool(description = "Display stored images from an uploaded PDF to the user (photos, graphs, charts, "
            + "diagrams, maps, screenshots — not only plots). Use when the user asks to see a figure, graph, "
            + "photo, image, or chart, or when your answer discusses a graph. Filename must match listDocuments. "
            + "Page numbers are 1-based; pass page 0 to show stored images across pages of that file "
            + "(graphs first, different pages, capped). "
            + "Paste each markdown image immediately after the sentence it supports — never as a trailing list.")
    public String showDocumentImages(String filename, int page, ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> ctx = context(toolContext);
        String conversationId = str(ctx.get("conversationId"));
        QueryTurnContext turn = QueryTurnContext.get(conversationId);
        if (turn != null) {
            turn.noteTool("showDocumentImages");
        }
        try {
            if (filename == null || filename.isBlank()) {
                return "A filename is required. Call listDocuments to see uploaded names.";
            }
            List<DocumentFigureView> figures;
            if (page < 1) {
                figures = figureRepo.findByFilenameOrderByPageNumberAscFigureIndexAsc(filename.trim());
            } else {
                figures = figureRepo.findByFilenameAndPageNumberOrderByFigureIndexAsc(filename.trim(), page);
            }
            figures = visualFigures(figures);
            if (figures == null || figures.isEmpty()) {
                return "No stored images for '" + filename.trim()
                        + (page < 1 ? "'." : "' page " + page + ".")
                        + " Images are captured at upload for PDFs that contain pictures or graphs.";
            }
            if (page < 1) {
                figures = diversifyFiguresByPage(figures, 8);
            } else if (figures.size() > 8) {
                figures = rankedVisuals(figures, 8);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Showing ").append(figures.size()).append(" image(s). ")
                    .append("Paste each markdown image immediately after the sentence it illustrates. ")
                    .append("Do not collect images at the end of the answer.\n");
            for (DocumentFigureView fig : figures) {
                if (turn != null) {
                    turn.addExplicitFigure(figurePayload(fig));
                    turn.addCitation(fig.getFilename(), fig.getPageNumber());
                }
                String url = "/document/figures/" + fig.getId();
                String alt = fig.getTitle() == null || fig.getTitle().isBlank()
                        ? ("Image page " + fig.getPageNumber())
                        : fig.getTitle();
                sb.append("\n![")
                        .append(alt.replace("]", ""))
                        .append("](").append(url).append(")\n");
                sb.append("[SOURCE: ").append(fig.getFilename())
                        .append(", PAGE: ").append(fig.getPageNumber()).append("] [KIND: ")
                        .append(fig.getKind()).append("]\n");
                if (fig.getCaption() != null && !fig.getCaption().isBlank()) {
                    sb.append(fig.getCaption()).append('\n');
                }
            }
            return sb.toString().trim();
        } finally {
            if (turn != null) {
                turn.emitToolEnd("showDocumentImages", System.currentTimeMillis() - t0);
            }
        }
    }

    private String describeStoredImages(String filename, int page, String conversationId, boolean attachExplicit) {
        List<DocumentFigureView> figures = visualFigures(
                figureRepo.findByFilenameAndPageNumberOrderByFigureIndexAsc(filename, page));
        if (figures == null || figures.isEmpty()) {
            return "";
        }
        QueryTurnContext turn = QueryTurnContext.get(conversationId);
        StringBuilder sb = new StringBuilder();
        sb.append("This page has ").append(figures.size())
                .append(" stored image(s) (photo/graph/diagram). ");
        sb.append("If you use this page, paste the markdown image immediately after that sentence. ");
        sb.append("Call showDocumentImages(\"").append(filename).append("\", ").append(page)
                .append(") to get the markdown.");
        if (attachExplicit && turn != null) {
            for (DocumentFigureView fig : figures) {
                turn.addExplicitFigure(figurePayload(fig));
            }
        }
        return sb.toString();
    }

    static boolean isChatVisual(String kind) {
        if (kind == null || kind.isBlank()) {
            return true;
        }
        String k = kind.trim().toLowerCase();
        return !"page".equals(k) && !"preview".equals(k);
    }

    static int visualKindRank(String kind) {
        if (kind == null) {
            return 9;
        }
        return switch (kind.trim().toLowerCase()) {
            case "graph" -> 0;
            case "diagram" -> 1;
            case "table" -> 2;
            case "photo" -> 3;
            case "map" -> 4;
            case "screenshot" -> 5;
            default -> 8;
        };
    }

    static List<DocumentFigureView> rankedVisuals(List<DocumentFigureView> figures, int limit) {
        List<DocumentFigureView> visual = visualFigures(figures);
        if (visual == null || visual.isEmpty() || limit <= 0) {
            return visual == null ? List.of() : visual;
        }
        return visual.stream()
                .sorted((a, b) -> Integer.compare(visualKindRank(a.getKind()), visualKindRank(b.getKind())))
                .limit(limit)
                .toList();
    }

    static List<DocumentFigureView> visualFigures(List<DocumentFigureView> figures) {
        if (figures == null || figures.isEmpty()) {
            return figures;
        }
        return figures.stream().filter(f -> isChatVisual(f.getKind())).toList();
    }

    static Map<String, Object> figurePayload(DocumentFigureView fig) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("id", fig.getId().toString());
        m.put("filename", fig.getFilename());
        m.put("page", fig.getPageNumber());
        m.put("kind", fig.getKind() == null ? "other" : fig.getKind());
        m.put("title", fig.getTitle() == null ? "" : fig.getTitle());
        m.put("caption", fig.getCaption() == null ? "" : fig.getCaption());
        m.put("url", "/document/figures/" + fig.getId());
        return m;
    }

    private String maybeRewrite(String query, Map<String, Object> ctx, QueryTurnContext turn) {
        boolean rewrite = turn != null ? turn.isRewrite() : bool(ctx.get("rewrite"), false);
        if (!rewrite || query == null || query.isBlank()) {
            return query;
        }
        boolean graphish = QueryRewriterService.looksLikeGraphOrEstimate(query);
        if (!queryRewriter.shouldRewrite(query) && !(turn != null && turn.isCrossLingual()) && !graphish) {
            return query;
        }
        String language = turn != null ? turn.getLanguage() : str(ctx.get("language"));
        String model = turn != null ? turn.getModelName() : str(ctx.get("model"));
        boolean cross = (turn != null && turn.isCrossLingual())
                || bool(ctx.get("crossLingual"), false)
                || graphish;
        var history = turn != null ? turn.getHistory() : List.<org.springframework.ai.chat.messages.Message>of();
        return queryRewriter.rewriteQuery(query, history, language, model, cross);
    }

    /**
     * Fetch extra neighbors, optionally merge a number-stripped graph query, then keep
     * at most a couple of chunks per page so one plot (e.g. page 16) cannot crowd out others.
     */
    private List<Document> retrieveHits(String query, int topK, double threshold, String filename) {
        int fetchK = Math.min(50, Math.max(topK * 3, topK + 8));
        List<Document> primary = similarity(query, fetchK, threshold, filename);
        if (QueryRewriterService.looksLikeGraphOrEstimate(query) && QueryRewriterService.hasSignificantNumber(query)) {
            String broad = QueryRewriterService.stripNumbers(query)
                    + " graph figure chart plot absorbance mABS time axis range";
            List<Document> extra = similarity(broad, fetchK, threshold, filename);
            primary = mergeHits(primary, extra);
        }
        return diversifyByPage(primary, topK, QueryRewriterService.looksLikeGraphOrEstimate(query));
    }

    private List<Document> similarity(String query, int topK, double threshold, String filename) {
        var builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold);
        if (filename != null && !filename.isBlank()) {
            builder.filterExpression("filename == " + quoteFilter(filename));
        }
        List<Document> docs = vectorStore.similaritySearch(builder.build());
        return docs == null ? List.of() : docs;
    }

    private static List<Document> mergeHits(List<Document> a, List<Document> b) {
        LinkedHashMap<String, Document> byKey = new LinkedHashMap<>();
        for (Document d : a) {
            byKey.putIfAbsent(hitKey(d), d);
        }
        for (Document d : b) {
            byKey.putIfAbsent(hitKey(d), d);
        }
        return new ArrayList<>(byKey.values());
    }

    private static String hitKey(Document d) {
        if (d == null) {
            return "";
        }
        if (d.getId() != null && !String.valueOf(d.getId()).isBlank()) {
            return String.valueOf(d.getId());
        }
        return pageKey(d) + "|" + Integer.toHexString(String.valueOf(d.getText()).hashCode());
    }

    private static String pageKey(Document d) {
        Map<String, Object> meta = d == null ? null : d.getMetadata();
        String fn = meta == null ? "" : String.valueOf(meta.getOrDefault("filename", "")).trim().toLowerCase();
        Object page = meta == null ? null : meta.get("page_number");
        return fn + "|" + String.valueOf(page);
    }

    private static boolean isFigureHit(Document d) {
        if (d == null || d.getMetadata() == null) {
            return false;
        }
        Object kind = d.getMetadata().get("content_kind");
        if (kind != null && "figure".equalsIgnoreCase(kind.toString())) {
            return true;
        }
        Object figKind = d.getMetadata().get("figure_kind");
        if (figKind != null && !figKind.toString().isBlank()) {
            return true;
        }
        String text = d.getText() == null ? "" : d.getText();
        return text.contains("[FIGURE:");
    }

    static List<Document> diversifyByPage(List<Document> docs, int topK, boolean preferFigures) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        if (docs.size() <= topK) {
            return docs;
        }
        int maxPerPage = preferFigures ? 1 : 2;
        Map<String, Integer> perPage = new HashMap<>();
        List<Document> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        if (preferFigures) {
            for (Document d : docs) {
                if (out.size() >= topK) {
                    break;
                }
                if (!isFigureHit(d)) {
                    continue;
                }
                String key = pageKey(d);
                if (perPage.getOrDefault(key, 0) >= maxPerPage) {
                    continue;
                }
                perPage.put(key, perPage.getOrDefault(key, 0) + 1);
                out.add(d);
                used.add(hitKey(d));
            }
        }
        List<Document> overflow = new ArrayList<>();
        for (Document d : docs) {
            if (used.contains(hitKey(d))) {
                continue;
            }
            if (out.size() >= topK) {
                overflow.add(d);
                continue;
            }
            String key = pageKey(d);
            if (perPage.getOrDefault(key, 0) >= maxPerPage) {
                overflow.add(d);
                continue;
            }
            perPage.put(key, perPage.getOrDefault(key, 0) + 1);
            out.add(d);
            used.add(hitKey(d));
        }
        for (Document d : overflow) {
            if (out.size() >= topK) {
                break;
            }
            out.add(d);
        }
        return out;
    }

    static List<DocumentFigureView> diversifyFiguresByPage(List<DocumentFigureView> figures, int limit) {
        if (figures == null || figures.isEmpty() || limit <= 0) {
            return figures == null ? List.of() : figures;
        }
        List<DocumentFigureView> ranked = figures.stream()
                .sorted((a, b) -> Integer.compare(visualKindRank(a.getKind()), visualKindRank(b.getKind())))
                .toList();
        List<DocumentFigureView> out = new ArrayList<>();
        Set<Integer> pages = new HashSet<>();
        List<DocumentFigureView> extras = new ArrayList<>();
        for (DocumentFigureView fig : ranked) {
            Integer page = fig.getPageNumber();
            if (page != null && pages.contains(page)) {
                extras.add(fig);
                continue;
            }
            if (page != null) {
                pages.add(page);
            }
            out.add(fig);
            if (out.size() >= limit) {
                return out;
            }
        }
        for (DocumentFigureView fig : extras) {
            if (out.size() >= limit) {
                break;
            }
            out.add(fig);
        }
        return out;
    }

    private String formatHits(List<Document> docs, String conversationId) {
        if (docs == null || docs.isEmpty()) {
            return "No relevant content was found in the uploaded documents for that query.";
        }
        String joined = docs.stream().map(this::formatHit).collect(Collectors.joining("\n\n---\n\n"));
        collectCitations(joined, conversationId);
        return joined;
    }

    private String formatHit(Document doc) {
        String text = doc == null || doc.getText() == null ? "" : doc.getText();
        String figureId = str(doc.getMetadata() == null ? null : doc.getMetadata().get("figure_id"));
        String kind = str(doc.getMetadata() == null ? null : doc.getMetadata().get("figure_kind"));
        if (figureId == null || figureId.isBlank()) {
            Matcher idMatch = FIGURE_ID.matcher(text);
            if (idMatch.find()) {
                figureId = idMatch.group(1);
            }
        }
        if (kind == null || kind.isBlank()) {
            Matcher kindMatch = FIGURE_KIND.matcher(text);
            if (kindMatch.find()) {
                kind = kindMatch.group(1).trim();
            }
        }
        if (figureId != null && !figureId.isBlank() && isChatVisual(kind)) {
            String alt = (kind == null || kind.isBlank()) ? "figure" : kind;
            text = text + "\n\nPlace this image immediately after the sentence it supports (not at the end):\n"
                    + "![" + alt.replace("]", "") + "](/document/figures/" + figureId + ")";
        }
        return text;
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
