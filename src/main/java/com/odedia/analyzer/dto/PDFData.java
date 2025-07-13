package com.odedia.analyzer.dto;

import java.util.List;

public class PDFData {
    private final List<String> stringPages;
    private final String language;

    public PDFData(List<String> stringPages, String language) {
        this.stringPages = stringPages;
        this.language = language;
    }

    public List<String> getStringPages() {
        return stringPages;
    }

    public String getLanguage() {
        return language;
    }
}
