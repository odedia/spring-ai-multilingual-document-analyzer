package com.odedia.analyzer.vision;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import com.odedia.analyzer.dto.ExtractedFigure;
import com.odedia.analyzer.dto.FigureCaption;
import com.odedia.analyzer.services.ChatModelRegistry;
import com.odedia.repo.jpa.DocumentFigureRepository;
import com.odedia.repo.model.DocumentFigure;

@Service
public class VisionCaptionService {

	private static final Logger logger = LoggerFactory.getLogger(VisionCaptionService.class);
	private static final Set<String> KINDS = Set.of(
			"photo", "graph", "diagram", "table", "map", "screenshot", "page", "other");

	private final ChatModelRegistry chatModelRegistry;
	private final DocumentFigureRepository figureRepo;

	public VisionCaptionService(ChatModelRegistry chatModelRegistry, DocumentFigureRepository figureRepo) {
		this.chatModelRegistry = chatModelRegistry;
		this.figureRepo = figureRepo;
	}

	public DocumentFigure captionAndSave(ExtractedFigure extracted, String visionModel, String language) {
		FigureCaption caption = describe(extracted, visionModel, language);
		DocumentFigure row = new DocumentFigure();
		row.setFilename(extracted.filename());
		row.setPageNumber(extracted.pageNumber());
		row.setFigureIndex(extracted.figureIndex());
		row.setKind(normalizeKind(caption == null ? null : caption.kind(), extracted.source()));
		row.setTitle(trimTo(caption == null ? null : caption.title(), 500));
		row.setCaption(joinCaption(caption));
		row.setReadableText(caption == null ? null : caption.readableText());
		row.setMimeType("image/jpeg");
		row.setWidth(extracted.width());
		row.setHeight(extracted.height());
		row.setImageData(extracted.jpeg());
		row.setCreatedAt(Instant.now());
		return figureRepo.save(row);
	}

	public DocumentFigure savePagePreview(ExtractedFigure extracted) {
		DocumentFigure row = new DocumentFigure();
		row.setFilename(extracted.filename());
		row.setPageNumber(extracted.pageNumber());
		row.setFigureIndex(extracted.figureIndex());
		row.setKind("preview");
		row.setTitle("Page " + extracted.pageNumber());
		row.setCaption(extracted.filename());
		row.setReadableText(null);
		row.setMimeType("image/jpeg");
		row.setWidth(extracted.width());
		row.setHeight(extracted.height());
		row.setImageData(extracted.jpeg());
		row.setCreatedAt(Instant.now());
		return figureRepo.save(row);
	}

	private FigureCaption describe(ExtractedFigure extracted, String visionModel, String language) {
		if (extracted == null || extracted.jpeg() == null || extracted.jpeg().length == 0) {
			return fallback(extracted);
		}
		if (visionModel == null || visionModel.isBlank()) {
			return fallback(extracted);
		}
		try {
			ChatClient client = chatModelRegistry.clientFor(visionModel);
			String lang = "he".equalsIgnoreCase(language) ? "Hebrew" : "English";
			String prompt = """
					You are captioning an image extracted from a PDF (page %d of %s).
					It may be a photograph, chart/graph, diagram, table, map, screenshot, or a full page.
					Reply with structured fields only.
					kind: one of photo, graph, diagram, table, map, screenshot, page, other
					title: short title
					description: 2–6 sentences in %s describing what is shown (axes, trends, objects, setting)
					readableText: any text, numbers, axis labels, or legend entries you can read
					searchText: English keywords that would help find this image later.
					For graphs/charts you MUST include Y-axis name, units, and numeric min–max, and
					X-axis name, units, and numeric min–max (example: "Absorbance mABS 0-2500 Time min 0-30").
					""".formatted(extracted.pageNumber(), extracted.filename(), lang);
			ByteArrayResource resource = new ByteArrayResource(extracted.jpeg()) {
				@Override
				public String getFilename() {
					return "figure.jpg";
				}
			};
			FigureCaption caption = client.prompt()
					.user(u -> u.text(prompt).media(MimeTypeUtils.IMAGE_JPEG, resource))
					.call()
					.entity(FigureCaption.class);
			if (caption == null || (isBlank(caption.description()) && isBlank(caption.title()))) {
				return fallback(extracted);
			}
			return caption;
		} catch (Exception e) {
			logger.warn("Vision caption failed for {} page {} (model={}): {}",
					extracted.filename(), extracted.pageNumber(), visionModel, e.getMessage());
			return fallback(extracted);
		}
	}

	private static FigureCaption fallback(ExtractedFigure extracted) {
		if (extracted == null) {
			return new FigureCaption("other", "Image", "Image from the uploaded document.", "", "image figure");
		}
		String kind = "page".equals(extracted.source()) ? "page" : "other";
		String title = "Image on page " + extracted.pageNumber();
		String description = "Visual from " + extracted.filename() + ", page " + extracted.pageNumber()
				+ " (" + extracted.source() + ").";
		return new FigureCaption(kind, title, description, "", "image figure " + extracted.filename());
	}

	private static String joinCaption(FigureCaption caption) {
		if (caption == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		if (!isBlank(caption.description())) {
			sb.append(caption.description().trim());
		}
		if (!isBlank(caption.searchText())) {
			if (sb.length() > 0) {
				sb.append("\n\n");
			}
			sb.append(caption.searchText().trim());
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	private static String normalizeKind(String kind, String source) {
		if (kind != null) {
			String k = kind.trim().toLowerCase(Locale.ROOT);
			if (KINDS.contains(k)) {
				return k;
			}
		}
		return "page".equals(source) ? "page" : "other";
	}

	private static String trimTo(String s, int max) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.length() <= max ? t : t.substring(0, max);
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
