package com.odedia.analyzer.chunking;

import org.springframework.ai.document.Document;

import com.odedia.repo.model.DocumentFigure;

public final class FigureChunkFactory {

	private FigureChunkFactory() {
	}

	public static Document from(DocumentFigure fig, String language) {
		StringBuilder body = new StringBuilder();
		body.append("[SOURCE: ").append(fig.getFilename())
				.append(", PAGE: ").append(fig.getPageNumber()).append("] ")
				.append("[FIGURE: ").append(fig.getId()).append("] ")
				.append("[KIND: ").append(nullToEmpty(fig.getKind())).append("]\n\n");
		if (fig.getTitle() != null && !fig.getTitle().isBlank()) {
			body.append(fig.getTitle().trim()).append("\n\n");
		}
		if (fig.getCaption() != null && !fig.getCaption().isBlank()) {
			body.append(fig.getCaption().trim()).append("\n\n");
		}
		if (fig.getReadableText() != null && !fig.getReadableText().isBlank()) {
			body.append(fig.getReadableText().trim());
		}
		Document doc = new Document(body.toString().trim());
		doc.getMetadata().put("filename", fig.getFilename());
		doc.getMetadata().put("language", language == null ? "en" : language);
		doc.getMetadata().put("page_number", fig.getPageNumber());
		doc.getMetadata().put("content_kind", "figure");
		doc.getMetadata().put("figure_id", fig.getId().toString());
		doc.getMetadata().put("figure_kind", nullToEmpty(fig.getKind()));
		return doc;
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}
}
