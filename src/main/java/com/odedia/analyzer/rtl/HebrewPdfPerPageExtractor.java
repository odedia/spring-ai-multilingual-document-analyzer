package com.odedia.analyzer.rtl;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import com.odedia.analyzer.file.FileMultipartFile;

public class HebrewPdfPerPageExtractor {

	private static final Logger logger = LoggerFactory.getLogger(HebrewPdfPerPageExtractor.class);

    /**
     * Extracts text per page, skipping the first and last line of each page.
     *
     * @param pdfFile the PDF file
     * @return a list of strings, one per page
     * @throws IOException
     */
    
	public static List<String> extractParagraphs(MultipartFile pdfFile) throws IOException {
	    File file = File.createTempFile("prefix-", ".tmp");
	    pdfFile.transferTo(file);

	    try (PDDocument document = Loader.loadPDF(file)) {
	        PDFTextStripper stripper = new PDFTextStripper();
	        stripper.setSortByPosition(true);

	        // Extract the entire text at once
	        String rawText = stripper.getText(document);

	        // Split into lines (treating each as a paragraph)
	        String[] lines = rawText.split("\\r?\\n");

	        List<String> paragraphs = new ArrayList<>();
	        for (String line : lines) {
	            String trimmed = line.trim();
	            if (trimmed.isEmpty()) continue;

//	            if (isMostlyHebrew(trimmed) && looksReversed(trimmed)) {
//	                trimmed = reverseWords(trimmed);
//	            }

	            paragraphs.add(trimmed);
	        }

	        // Optional: log
	        for (int i = 0; i < paragraphs.size(); i++) {
	            logger.info("Paragraph {}:\n{}\n", i + 1, paragraphs.get(i));
	        }

	        return paragraphs;
	    }
	}

	public static List<String> extractPages(MultipartFile pdfFile, Boolean isHebrew) throws IOException {
    	File file = File.createTempFile("prefix-", ".tmp");
    	pdfFile.transferTo(file);

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            
            if (isHebrew) stripper.setSortByPosition(true);

            List<String> pages = new ArrayList<>();
            int total = document.getNumberOfPages();

            for (int p = 1; p <= total; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);

                // get the raw text of page p
                String raw = stripper.getText(document);
                String[] lines = raw.split("\\r?\\n");

                // drop first and last line (assumed header/footer)
                int from = Math.min(1, lines.length);
                int to   = Math.max(lines.length - 2, from);
                List<String> bodyLines = Arrays.asList(lines).subList(from, to);

                // re-join, skipping any blank lines
                StringBuilder clean = new StringBuilder();
                for (String line : bodyLines) {
                    if (!line.trim().isEmpty()) {
                        clean.append(line).append("\n");
                    }
                }
                pages.add(clean.toString().trim());
            }
            int x=0;
            for (String page : pages) {
            	x++;
            	logger.info("page {}: \n\n {} \n\n", x, page);
            }
            return pages;
        }
    }

	/**
	 * Helper main method to test out parsing. Send the path to the PDF as first arg.
	 * @param args
	 */
	public static void main(String[] args) {
        File pdf = new File(args[0]);
        MultipartFile multipartFile = new FileMultipartFile(pdf);

        try {
            List<String> pages = extractPages(multipartFile, true);
            for (int i = 0; i < pages.size(); i++) {
                System.out.println("===== PAGE " + (i+1) + " =====");
                System.out.println(pages.get(i));
                System.out.println();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
