package com.odedia.analyzer.rtl;

import com.odedia.analyzer.dto.PDFData;
import com.odedia.analyzer.file.FileMultipartFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts text from PDF documents with special support for Hebrew (RTL) text.
 *
 * Key features:
 * - Automatic language detection (Hebrew vs English)
 * - Proper RTL text extraction using setSortByPosition for Hebrew
 * - Per-page extraction for downstream chunking
 * - Header/footer removal
 * - Blank line filtering
 *
 * Hebrew Support:
 * - Detects Hebrew text using Unicode range U+0590 to U+05FF
 * - Enables position-based sorting for correct character ordering in RTL text
 * - Works seamlessly with mixed Hebrew-English documents
 */
public class HebrewEnglishPdfPerPageExtractor {

	private static final Logger logger = LoggerFactory.getLogger(HebrewEnglishPdfPerPageExtractor.class);

	// Hebrew Unicode block range
	private static final char HEBREW_START = '\u0590';
	private static final char HEBREW_END = '\u05FF';

	/**
	 * Extracts text from a PDF file, page by page, with Hebrew language support.
	 *
	 * @param pdfFile The PDF file as a MultipartFile
	 * @return PDFData containing extracted pages and detected language
	 * @throws IOException if PDF reading fails
	 */
	public static PDFData extractPages(MultipartFile pdfFile) throws IOException {
		File tempFile = File.createTempFile("pdf-extract-", ".tmp");
		try {
			pdfFile.transferTo(tempFile);

			try (PDDocument document = Loader.loadPDF(tempFile)) {
				// Detect language from entire document
				PDFTextStripper stripper = new PDFTextStripper();
				String fullText = stripper.getText(document);
				String language = detectDominantLanguage(fullText);
				boolean isHebrew = "he".equals(language);

				// Enable position-based sorting for Hebrew (critical for RTL text)
				if (isHebrew) {
					stripper.setSortByPosition(true);
					logger.debug("Detected Hebrew text, enabling position-based sorting");
				}

				List<String> pages = new ArrayList<>();
				int totalPages = document.getNumberOfPages();
				logger.info("Extracting {} pages from PDF (language: {})", totalPages, language);

				// Extract each page
				for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
					stripper.setStartPage(pageNum);
					stripper.setEndPage(pageNum);

					String rawText = stripper.getText(document);
					String cleanedPage = cleanPageText(rawText);

					if (!cleanedPage.isEmpty()) {
						pages.add(cleanedPage);
					}
				}

				logger.info("Extracted {} non-empty pages", pages.size());
				return new PDFData(pages, language);
			}
		} finally {
			// Clean up temp file
			if (tempFile.exists()) {
				tempFile.delete();
			}
		}
	}

	/**
	 * Cleans page text by removing headers/footers and blank lines.
	 *
	 * @param rawText Raw text from PDF page
	 * @return Cleaned text
	 */
	private static String cleanPageText(String rawText) {
		if (rawText == null || rawText.isEmpty()) {
			return "";
		}

		String[] lines = rawText.split("\\r?\\n");
		if (lines.length == 0) {
			return "";
		}

		// Remove assumed header (first line) and footer (last 2 lines)
		int startLine = Math.min(1, lines.length);
		int endLine = Math.max(lines.length - 2, startLine);
		List<String> bodyLines = Arrays.asList(lines).subList(startLine, endLine);

		// Re-join, skipping blank lines
		StringBuilder cleaned = new StringBuilder();
		for (String line : bodyLines) {
			if (!line.trim().isEmpty()) {
				cleaned.append(line).append("\n");
			}
		}

		return cleaned.toString().trim();
	}

	/**
	 * Detects the dominant language in the text by counting Hebrew vs English characters.
	 *
	 * @param text The text to analyze
	 * @return "he" for Hebrew, "en" for English
	 */
	public static String detectDominantLanguage(String text) {
		if (text == null || text.isEmpty()) {
			return "en";
		}

		int hebrewChars = 0;
		int englishChars = 0;

		for (char c : text.toCharArray()) {
			// Count Hebrew characters (Unicode block U+0590 to U+05FF)
			if (c >= HEBREW_START && c <= HEBREW_END) {
				hebrewChars++;
			}
			// Count English letters
			else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
				englishChars++;
			}
		}

		logger.debug("Language detection: {} Hebrew chars, {} English chars", hebrewChars, englishChars);
		return (hebrewChars >= englishChars) ? "he" : "en";
	}

	/**
	 * CLI utility for testing PDF extraction.
	 * Usage: java HebrewEnglishPdfPerPageExtractor <path-to-pdf>
	 *
	 * @param args Command line arguments (expects PDF file path)
	 */
	public static void main(String[] args) {
		if (args.length == 0) {
			System.err.println("Usage: java HebrewEnglishPdfPerPageExtractor <path-to-pdf>");
			System.exit(1);
		}

		File pdfFile = new File(args[0]);
		if (!pdfFile.exists() || !pdfFile.isFile()) {
			System.err.println("Error: File not found or not a file: " + args[0]);
			System.exit(1);
		}

		MultipartFile multipartFile = new FileMultipartFile(pdfFile);

		try {
			PDFData pdfData = extractPages(multipartFile);
			List<String> pages = pdfData.getStringPages();
			String language = pdfData.getLanguage();

			System.out.println("===== PDF EXTRACTION RESULTS =====");
			System.out.println("Language: " + language);
			System.out.println("Total pages: " + pages.size());
			System.out.println();

			for (int i = 0; i < pages.size(); i++) {
				System.out.println("===== PAGE " + (i + 1) + " =====");
				System.out.println(pages.get(i));
				System.out.println();
			}
		} catch (IOException e) {
			System.err.println("Error extracting PDF: " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}
}
