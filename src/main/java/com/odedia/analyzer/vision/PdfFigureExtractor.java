package com.odedia.analyzer.vision;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.odedia.analyzer.dto.ExtractedFigure;
import com.odedia.analyzer.dto.PDFData;
import com.odedia.analyzer.dto.PDFData.PageData;

/**
 * Pulls bitmaps (photos, scans, embedded plots) and rasterizes pages that hold
 * vector graphs or figure captions. Tiny logos are skipped.
 */
@Component
public class PdfFigureExtractor {

	private static final Logger logger = LoggerFactory.getLogger(PdfFigureExtractor.class);
	private static final int MIN_EDGE = 80;
	private static final int MAX_EDGE = 1280;
	private static final float RENDER_DPI = 150f;
	private static final float JPEG_QUALITY = 0.82f;
	/** Stored as kind=page for citation previews; not vision-captioned or shown in chat. */
	public static final int PAGE_PREVIEW_INDEX = 1000;

	public List<ExtractedFigure> extract(File pdfFile, String filename, PDFData pdfData) throws IOException {
		Map<Integer, Integer> textLen = new HashMap<>();
		if (pdfData != null) {
			for (PageData page : pdfData.getPages()) {
				String content = page.getContent() == null ? "" : page.getContent();
				textLen.put(page.getActualPageNumber(), content.length());
			}
		}

		List<ExtractedFigure> out = new ArrayList<>();
		try (PDDocument document = Loader.loadPDF(pdfFile)) {
			PDFRenderer renderer = new PDFRenderer(document);
			renderer.setSubsamplingAllowed(true);
			int pages = document.getNumberOfPages();
			for (int i = 0; i < pages; i++) {
				int pageNum = i + 1;
				PDPage page = document.getPage(i);
				List<BufferedImage> bitmaps = new ArrayList<>();
				boolean hasForm = collectGraphics(page.getResources(), bitmaps, new HashSet<>());
				List<BufferedImage> kept = bitmaps.stream().filter(im -> !tooSmall(im)).toList();
				int textChars = textLen.getOrDefault(pageNum, 0);
				boolean imageOnlyPage = textChars < 80 && !kept.isEmpty();
				boolean likelyScan = imageOnlyPage && kept.size() == 1 && coversMostOfPage(kept.get(0), page);

				int figIndex = 0;
				if (!likelyScan) {
					for (BufferedImage im : kept) {
						byte[] jpeg = toJpeg(im);
						if (jpeg.length == 0) {
							continue;
						}
						out.add(new ExtractedFigure(filename, pageNum, figIndex++, "xobject",
								im.getWidth(), im.getHeight(), jpeg));
					}
				}

				// Full-page raster for vision only when the page *is* the image (scan) or a
				// vector graphic with almost no text. Do not screenshot every page that merely
				// mentions "Figure" / "Table" — those belong in citation previews instead.
				boolean needPageRaster = likelyScan
						|| (kept.isEmpty() && hasForm && textChars < 300);
				if (needPageRaster) {
					try {
						BufferedImage raster = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
						if (!tooSmall(raster)) {
							byte[] jpeg = toJpeg(raster);
							if (jpeg.length > 0) {
								out.add(new ExtractedFigure(filename, pageNum, figIndex, "page",
										raster.getWidth(), raster.getHeight(), jpeg));
							}
						}
					} catch (Exception e) {
						logger.warn("Failed to rasterize page {} of {}: {}", pageNum, filename, e.getMessage());
					}
				}
			}
		}
		logger.info("Extracted {} image(s) from {}", out.size(), filename);
		return out;
	}

	/** Rasterize each page for the citation-chip dialog. Not sent to the vision model. */
	public List<ExtractedFigure> renderPagePreviews(File pdfFile, String filename) throws IOException {
		List<ExtractedFigure> out = new ArrayList<>();
		try (PDDocument document = Loader.loadPDF(pdfFile)) {
			PDFRenderer renderer = new PDFRenderer(document);
			renderer.setSubsamplingAllowed(true);
			int pages = document.getNumberOfPages();
			for (int i = 0; i < pages; i++) {
				try {
					BufferedImage raster = renderer.renderImageWithDPI(i, 110f, ImageType.RGB);
					if (tooSmall(raster)) {
						continue;
					}
					byte[] jpeg = toJpeg(raster);
					if (jpeg.length == 0) {
						continue;
					}
					out.add(new ExtractedFigure(filename, i + 1, PAGE_PREVIEW_INDEX, "page",
							raster.getWidth(), raster.getHeight(), jpeg));
				} catch (Exception e) {
					logger.warn("Failed to render page preview {} of {}: {}", i + 1, filename, e.getMessage());
				}
			}
		}
		logger.info("Rendered {} page preview(s) from {}", out.size(), filename);
		return out;
	}

	private boolean collectGraphics(PDResources resources, List<BufferedImage> bitmaps, Set<COSBase> seen) {
		if (resources == null) {
			return false;
		}
		boolean hasForm = false;
		for (var name : resources.getXObjectNames()) {
			PDXObject xobject;
			try {
				xobject = resources.getXObject(name);
			} catch (IOException e) {
				continue;
			}
			if (xobject == null) {
				continue;
			}
			COSBase key = xobject.getCOSObject();
			if (!seen.add(key)) {
				continue;
			}
			if (xobject instanceof PDImageXObject image) {
				try {
					BufferedImage bi = image.getImage();
					if (bi != null) {
						bitmaps.add(bi);
					}
				} catch (Exception e) {
					logger.debug("Skip unreadable image XObject: {}", e.getMessage());
				}
			} else if (xobject instanceof PDFormXObject form) {
				hasForm = true;
				collectGraphics(form.getResources(), bitmaps, seen);
			}
		}
		return hasForm;
	}

	private static boolean tooSmall(BufferedImage im) {
		return im == null || im.getWidth() < MIN_EDGE || im.getHeight() < MIN_EDGE;
	}

	private static boolean coversMostOfPage(BufferedImage im, PDPage page) {
		PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
		if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) {
			return true;
		}
		double pageArea = box.getWidth() * box.getHeight();
		double imgArea = (double) im.getWidth() * im.getHeight();
		return imgArea > pageArea * 0.5;
	}

	private byte[] toJpeg(BufferedImage src) {
		try {
			BufferedImage rgb = src;
			if (src.getType() != BufferedImage.TYPE_INT_RGB) {
				rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
				Graphics2D g = rgb.createGraphics();
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, src.getWidth(), src.getHeight());
				g.drawImage(src, 0, 0, null);
				g.dispose();
			}
			int maxDim = Math.max(rgb.getWidth(), rgb.getHeight());
			if (maxDim > MAX_EDGE) {
				double scale = MAX_EDGE / (double) maxDim;
				int w = Math.max(1, (int) Math.round(rgb.getWidth() * scale));
				int h = Math.max(1, (int) Math.round(rgb.getHeight() * scale));
				BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = scaled.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(rgb, 0, 0, w, h, null);
				g.dispose();
				rgb = scaled;
			}
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
			if (!writers.hasNext()) {
				ImageIO.write(rgb, "jpg", baos);
				return baos.toByteArray();
			}
			ImageWriter writer = writers.next();
			ImageWriteParam param = writer.getDefaultWriteParam();
			if (param.canWriteCompressed()) {
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(JPEG_QUALITY);
			}
			try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
				writer.setOutput(ios);
				writer.write(null, new IIOImage(rgb, null, null), param);
			} finally {
				writer.dispose();
			}
			return baos.toByteArray();
		} catch (Exception e) {
			logger.warn("JPEG encode failed: {}", e.getMessage());
			return new byte[0];
		}
	}
}
