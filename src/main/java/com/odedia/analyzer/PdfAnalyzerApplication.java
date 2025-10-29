package com.odedia.analyzer;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.unit.DataSize;

import com.odedia.analyzer.memory.SummarizingTokenWindowChatMemory;
import com.odedia.analyzer.services.MessageSummarizationService;

import jakarta.servlet.MultipartConfigElement;

@SpringBootApplication(scanBasePackages = {
	    "com.odedia.analyzer",
	    "com.odedia.repo" })
@EnableJpaRepositories(basePackages = "com.odedia.repo.jpa")
@EntityScan(basePackages = "com.odedia.repo.model")
public class PdfAnalyzerApplication {

	public static void main(String[] args) {
		SpringApplication.run(PdfAnalyzerApplication.class, args);
	}

    @Bean
    public ChatMemory chatMemory(
            ChatMemoryRepository chatMemoryRepository,
            MessageSummarizationService summarizationService,
            @Value("${app.ai.maxChatTokens}") int maxTokens,
            @Value("${app.ai.recentMessageCount}") int recentMessageCount) {
        return SummarizingTokenWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .summarizationService(summarizationService)
                .maxTokens(maxTokens)
                .recentMessageCount(recentMessageCount)
                .build();
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
	
    @Bean
    public ChatMemoryRepository chatMemoryRepository(JdbcTemplate jdbcTemplate) {
        return JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new PostgresChatMemoryRepositoryDialect())
                .build();
    }
}
