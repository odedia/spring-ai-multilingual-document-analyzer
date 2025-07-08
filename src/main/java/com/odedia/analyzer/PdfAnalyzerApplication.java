package com.odedia.analyzer;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.util.unit.DataSize;

import jakarta.servlet.MultipartConfigElement;

@SpringBootApplication
public class PdfAnalyzerApplication {

	public static void main(String[] args) {
		SpringApplication.run(PdfAnalyzerApplication.class, args);
	}

	@Bean
	public ChatMemory chatMemory() {
		return MessageWindowChatMemory.builder().build();
	}
	
	@Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        
        // Set maximum file size
        factory.setMaxFileSize(DataSize.ofMegabytes(50));
        
        // Set maximum request size (total file size)
        factory.setMaxRequestSize(DataSize.ofMegabytes(50));
        
        // Set location for temporary files
        factory.setLocation("");
        
        return factory.createMultipartConfig();
    }
}
