package com.odedia.analyzer.dto;

public class DocumentInfo {
    private final String filename;
    private final String language;
    private final int chunks;
    private final int pages;

    public DocumentInfo(String filename, String language) {
        this(filename, language, 0, 0);
    }

    public DocumentInfo(String filename, String language, int chunks, int pages) {
        this.filename = filename;
        this.language = language;
        this.chunks = chunks;
        this.pages = pages;
    }

    public String getFilename() {
        return filename;
    }

    public String getLanguage() {
        return language;
    }

    public int getChunks() {
        return chunks;
    }

    public int getPages() {
        return pages;
    }
}
