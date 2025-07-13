package com.odedia.analyzer.dto;

public class DocumentInfo {
    private final String filename;
    private final String language;

    public DocumentInfo(String filename, String language) {
        this.filename = filename;
        this.language = language;
    }

    public String getFilename() {
        return filename;
    }

    public String getLanguage() {
        return language;
    }
}
