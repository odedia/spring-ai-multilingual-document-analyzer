package com.odedia.analyzer.chunking;

import com.odedia.analyzer.dto.PDFData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adaptive semantic chunker that intelligently splits documents based on structure
 * while maintaining context through overlapping chunks.
 *
 * Features:
 * - Respects semantic boundaries (sections, paragraphs)
 * - Maintains context with configurable overlap
 * - Supports both Hebrew and English text
 * - Adapts chunk size based on document structure
 */
public class AdaptiveSemanticChunker {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveSemanticChunker.class);

    private static final int MIN_CHUNK_SIZE = 256;      // Don't create tiny chunks
    private static final int TARGET_CHUNK_SIZE = 384;   // Ideal size: ~3-4 paragraphs
    private static final int MAX_CHUNK_SIZE = 512;      // Hard upper limit (nomic-embed-text safe limit)
    private static final int OVERLAP_SIZE = 100;        // Context overlap between chunks
    private static final int CHARS_PER_TOKEN = 4;       // Approximation for token counting

    /**
     * Chunks a PDF document adaptively based on semantic structure.
     *
     * @param pdfData The extracted PDF data with pages and language
     * @param filename The original filename for metadata
     * @return List of chunked documents with metadata
     */
    public static List<Document> chunkDocument(PDFData pdfData, String filename) {
        List<Document> chunks = new ArrayList<>();

        // Combine all pages into full text
        String fullText = String.join("\n\n", pdfData.getStringPages());
        String language = pdfData.getLanguage();

        logger.info("Chunking document '{}' ({}) with {} pages, {} chars",
                    filename, language, pdfData.getStringPages().size(), fullText.length());

        // Detect major sections in the document
        List<Section> sections = detectSections(fullText, language);
        logger.debug("Detected {} sections in document", sections.size());

        StringBuilder currentChunk = new StringBuilder();
        String previousOverlap = "";
        int chunkIndex = 0;
        int currentPageHint = 1;

        for (Section section : sections) {
            int sectionTokens = estimateTokens(section.content);
            int currentTokens = estimateTokens(currentChunk.toString());

            logger.debug("Processing section: {} tokens, type: {}", sectionTokens, section.type);

            // Decision tree based on section size
            if (sectionTokens < MIN_CHUNK_SIZE) {
                // Small section: accumulate into current chunk
                currentChunk.append(section.content).append("\n\n");

            } else if (sectionTokens >= MIN_CHUNK_SIZE && sectionTokens <= MAX_CHUNK_SIZE) {
                // Perfect size: potentially make it its own chunk

                if (currentChunk.length() > 0) {
                    // Emit accumulated chunk first
                    String chunkContent = previousOverlap + currentChunk.toString();
                    emitChunk(chunks, chunkContent, filename, language, chunkIndex++, currentPageHint);
                    previousOverlap = extractOverlap(currentChunk.toString(), OVERLAP_SIZE, language);
                    currentChunk = new StringBuilder();
                }

                // Check if we should combine with previous overlap or make standalone
                if (previousOverlap.length() > 0 &&
                    estimateTokens(previousOverlap) + sectionTokens <= MAX_CHUNK_SIZE) {
                    // Include overlap and emit
                    String chunkContent = previousOverlap + section.content;
                    emitChunk(chunks, chunkContent, filename, language, chunkIndex++, currentPageHint);
                    previousOverlap = extractOverlap(section.content, OVERLAP_SIZE, language);
                } else {
                    // Emit section as standalone chunk
                    emitChunk(chunks, section.content, filename, language, chunkIndex++, currentPageHint);
                    previousOverlap = extractOverlap(section.content, OVERLAP_SIZE, language);
                }

            } else {
                // Large section: split it further
                if (currentChunk.length() > 0) {
                    // Emit accumulated chunk first
                    String chunkContent = previousOverlap + currentChunk.toString();
                    emitChunk(chunks, chunkContent, filename, language, chunkIndex++, currentPageHint);
                    previousOverlap = extractOverlap(currentChunk.toString(), OVERLAP_SIZE, language);
                    currentChunk = new StringBuilder();
                }

                // Split large section into sub-chunks
                List<String> subChunks = splitLargeSection(section.content, TARGET_CHUNK_SIZE, language);
                for (String subChunk : subChunks) {
                    String chunkContent = previousOverlap + subChunk;
                    emitChunk(chunks, chunkContent, filename, language, chunkIndex++, currentPageHint);
                    previousOverlap = extractOverlap(subChunk, OVERLAP_SIZE, language);
                }
            }

            // Check if current accumulated chunk is getting too large
            if (estimateTokens(currentChunk.toString()) >= TARGET_CHUNK_SIZE) {
                String chunkContent = previousOverlap + currentChunk.toString();
                emitChunk(chunks, chunkContent, filename, language, chunkIndex++, currentPageHint);
                previousOverlap = extractOverlap(currentChunk.toString(), OVERLAP_SIZE, language);
                currentChunk = new StringBuilder();
            }
        }

        // Emit final accumulated chunk if any
        if (currentChunk.length() > MIN_CHUNK_SIZE / CHARS_PER_TOKEN) {
            String chunkContent = previousOverlap + currentChunk.toString();
            emitChunk(chunks, chunkContent, filename, language, chunkIndex++, currentPageHint);
        }

        logger.info("Created {} chunks from document '{}' (avg {} tokens per chunk)",
                    chunks.size(), filename,
                    chunks.isEmpty() ? 0 : fullText.length() / CHARS_PER_TOKEN / chunks.size());

        return chunks;
    }

    /**
     * Detects major sections in the document based on structural markers.
     */
    private static List<Section> detectSections(String text, String language) {
        List<Section> sections = new ArrayList<>();

        // Patterns for section detection (works for both Hebrew and English):
        // 1. Headers (lines with : at end, or all caps, or numbered)
        // 2. Double line breaks (paragraph boundaries)
        // 3. Numbered sections (1., 2., etc.)

        // Split on major boundaries while keeping delimiters
        String[] parts = text.split("(?=\n\n)");

        StringBuilder currentSection = new StringBuilder();
        SectionType currentType = SectionType.PARAGRAPH;

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            // Check if this looks like a header
            if (isHeader(trimmed, language)) {
                // Emit previous section if substantial
                if (currentSection.length() > MIN_CHUNK_SIZE / CHARS_PER_TOKEN) {
                    sections.add(new Section(currentSection.toString().trim(), currentType));
                    currentSection = new StringBuilder();
                }
                currentType = SectionType.HEADER;
            }

            currentSection.append(part);

            // If we've accumulated a substantial section, emit it
            if (estimateTokens(currentSection.toString()) >= TARGET_CHUNK_SIZE) {
                sections.add(new Section(currentSection.toString().trim(), currentType));
                currentSection = new StringBuilder();
                currentType = SectionType.PARAGRAPH;
            }
        }

        // Add final section
        if (currentSection.length() > 0) {
            sections.add(new Section(currentSection.toString().trim(), currentType));
        }

        return sections;
    }

    /**
     * Determines if a text block looks like a header.
     * Works for both Hebrew and English.
     */
    private static boolean isHeader(String text, String language) {
        String firstLine = text.split("\n")[0].trim();

        // Check various header patterns:
        // 1. Ends with colon
        if (firstLine.endsWith(":") || firstLine.endsWith(":\u200f")) {  // \u200f is RTL mark
            return true;
        }

        // 2. Numbered section (1., 2., 1.1, etc.)
        if (Pattern.matches("^\\d+(\\.\\d+)*\\.?\\s+.*", firstLine)) {
            return true;
        }

        // 3. Short line (< 80 chars) followed by content
        if (firstLine.length() < 80 && text.split("\n").length > 1) {
            return true;
        }

        // 4. All uppercase (for English)
        if ("en".equals(language) && firstLine.equals(firstLine.toUpperCase()) &&
            firstLine.length() < 100) {
            return true;
        }

        return false;
    }

    /**
     * Splits a large section into smaller chunks at paragraph boundaries.
     */
    private static List<String> splitLargeSection(String section, int targetSize, String language) {
        List<String> chunks = new ArrayList<>();

        // Split by paragraphs (double line breaks)
        String[] paragraphs = section.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            int currentTokens = estimateTokens(current.toString());
            int paraTokens = estimateTokens(trimmed);

            // Would adding this paragraph exceed target?
            if (currentTokens + paraTokens > targetSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }

            current.append(trimmed).append("\n\n");
        }

        // Add final chunk
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    /**
     * Extracts overlap text from the end of a chunk for context continuity.
     * Works with both Hebrew and English, respecting sentence boundaries.
     */
    private static String extractOverlap(String text, int overlapTokens, String language) {
        int overlapChars = overlapTokens * CHARS_PER_TOKEN;
        if (text.length() <= overlapChars) {
            return text;
        }

        // Try to break at sentence boundary
        int startPos = text.length() - overlapChars;

        // Look for sentence endings (works for both Hebrew and English)
        // Hebrew and English both use . ! ? for sentence endings
        Pattern sentenceEnd = Pattern.compile("[.!?]\\s+");
        Matcher matcher = sentenceEnd.matcher(text);

        int lastSentenceEnd = -1;
        while (matcher.find()) {
            if (matcher.start() > startPos && matcher.start() < text.length() - 10) {
                lastSentenceEnd = matcher.end();
                break;
            }
        }

        if (lastSentenceEnd > startPos) {
            return text.substring(lastSentenceEnd).trim();
        }

        // Fallback: just take last N characters
        return text.substring(startPos).trim();
    }

    /**
     * Estimates token count from character count.
     */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.length() / CHARS_PER_TOKEN;
    }

    /**
     * Creates and adds a chunk document to the list with full metadata.
     */
    private static void emitChunk(List<Document> chunks, String content, String filename,
                                  String language, int index, int pageHint) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }

        Document doc = new Document(content.trim());
        doc.getMetadata().put("filename", filename);
        doc.getMetadata().put("language", language);
        doc.getMetadata().put("chunk_index", index);
        doc.getMetadata().put("chunk_size_tokens", estimateTokens(content));
        doc.getMetadata().put("page_hint", pageHint);

        chunks.add(doc);

        logger.debug("Emitted chunk {}: {} tokens", index, estimateTokens(content));
    }

    /**
     * Represents a section of the document.
     */
    private static class Section {
        String content;
        SectionType type;

        Section(String content, SectionType type) {
            this.content = content;
            this.type = type;
        }
    }

    /**
     * Types of document sections.
     */
    private enum SectionType {
        HEADER,
        PARAGRAPH,
        LIST
    }
}
