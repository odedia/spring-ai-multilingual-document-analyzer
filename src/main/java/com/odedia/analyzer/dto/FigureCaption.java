package com.odedia.analyzer.dto;

/**
 * Structured vision-model output for a document image (photo, graph, diagram, scan, …).
 */
public record FigureCaption(
		String kind,
		String title,
		String description,
		String readableText,
		String searchText) {
}
