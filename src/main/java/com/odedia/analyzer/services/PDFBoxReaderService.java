package com.odedia.analyzer.services;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

public class PDFBoxReaderService {

	private static final Logger logger = LoggerFactory.getLogger(PDFBoxReaderService.class);

    public static List<String> extractPages(MultipartFile pdfFile) throws IOException {
	    File file = File.createTempFile("prefix-", ".tmp");
	    pdfFile.transferTo(file);

        PDDocument document = Loader.loadPDF(file);
        
        PDFTextStripper stripper = new PDFTextStripper();
        List<String> pagesText = new ArrayList<>();

        int pageCount = document.getNumberOfPages();
        for (int i = 1; i <= pageCount; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String text = stripper.getText(document);
            pagesText.add(text.trim());
        }
        document.close();
        logger.info("\n\n{}\n\n",pagesText);
        return pagesText;
    }
}
