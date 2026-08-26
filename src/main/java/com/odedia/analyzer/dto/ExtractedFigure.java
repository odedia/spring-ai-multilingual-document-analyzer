package com.odedia.analyzer.dto;

public record ExtractedFigure(
		String filename,
		int pageNumber,
		int figureIndex,
		String source,
		int width,
		int height,
		byte[] jpeg) {
}
