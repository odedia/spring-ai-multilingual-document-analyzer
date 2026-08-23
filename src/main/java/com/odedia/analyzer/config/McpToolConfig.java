package com.odedia.analyzer.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.odedia.analyzer.services.DocumentTools;

@Configuration
@Profile("mcp")
public class McpToolConfig {

	@Bean
	ToolCallbackProvider documentToolCallbacks(DocumentTools documentTools) {
		return MethodToolCallbackProvider.builder().toolObjects(documentTools).build();
	}
}
