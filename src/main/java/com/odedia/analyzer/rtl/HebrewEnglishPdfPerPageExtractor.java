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

import com.odedia.analyzer.dto.PDFData;
import com.odedia.analyzer.file.FileMultipartFile;

public class HebrewEnglishPdfPerPageExtractor {

	private static final Logger logger = LoggerFactory.getLogger(HebrewEnglishPdfPerPageExtractor.class);

	public static PDFData extractPages(MultipartFile pdfFile) throws IOException {
    	File file = File.createTempFile("prefix-", ".tmp");
    	pdfFile.transferTo(file);

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String raw = stripper.getText(document);
            boolean isHebrew = "he".equals(detectDominantLanguage(raw));
            if (isHebrew) {
            	stripper.setSortByPosition(true);
            }

            List<String> pages = new ArrayList<>();
            int total = document.getNumberOfPages();

            for (int p = 1; p <= total; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);

                // get the raw text of page p
                raw = stripper.getText(document);
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
            
            return new PDFData(pages, (isHebrew ? "he" : "en"));
        }
    }

    public static String detectDominantLanguage(String text) {
        int hebrewChars = 0;
        int englishChars = 0;

        for (char c : text.toCharArray()) {
            if (c >= '\u0590' && c <= '\u05FF') hebrewChars++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) englishChars++;
        }

        if (hebrewChars >= englishChars) return "he";
        return "en";
    }

	
	/**
	 * Helper main method to test out parsing. Send the path to the PDF as first arg.
	 * @param args
	 */
	public static void main(String[] args) {
        File pdf = new File(args[0]);
        MultipartFile multipartFile = new FileMultipartFile(pdf);

        try {
            List<String> pages = extractPages(multipartFile).getStringPages();
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
