package com.odedia.analyzer.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.pivotal.cfenv.boot.genai.GenaiLocator;

/**
 * When the app is bound to Tanzu Platform 10.4 GenAI services, every bound
 * service contributes a {@link GenaiLocator} (see
 * {@link MultiGenaiLocatorConfiguration}). This config picks the first chat
 * model and first embedding model from across all locators and exposes them
 * as {@code @Primary} beans so the {@code ChatClient.Builder} and the
 * pgvector store get a working client even when Spring AI's OpenAI
 * auto-config also produced a (broken) one from the unresolved
 * {@code ${OPENAI_API_KEY}} placeholder kept for local dev.
 *
 * Per-request chat model overrides are handled by
 * {@link com.odedia.analyzer.services.ChatModelRegistry}.
 *
 * Locally (no bound GenAI services) the locator list is empty and the beans
 * return null, which causes Spring to skip them — the auto-configured
 * Ollama/OpenAI models remain in play.
 */
@Configuration
public class GenaiBindingConfig {

    private static final Logger logger = LoggerFactory.getLogger(GenaiBindingConfig.class);

    @Bean
    @Primary
    public ChatModel genaiChatModel(ObjectProvider<List<GenaiLocator>> locatorsProvider) {
        List<GenaiLocator> locators = locatorsProvider.getIfAvailable();
        if (locators == null || locators.isEmpty()) {
            return null;
        }
        for (GenaiLocator locator : locators) {
            try {
                ChatModel model = locator.getFirstAvailableChatModel();
                if (model != null) {
                    logger.info("Default chat model from GenaiLocator: {}", model.getClass().getSimpleName());
                    return model;
                }
            } catch (Exception e) {
                logger.debug("Locator could not provide a chat model: {}", e.getMessage());
            }
        }
        return null;
    }

    @Bean
    @Primary
    public EmbeddingModel genaiEmbeddingModel(ObjectProvider<List<GenaiLocator>> locatorsProvider) {
        List<GenaiLocator> locators = locatorsProvider.getIfAvailable();
        if (locators == null || locators.isEmpty()) {
            return null;
        }
        for (GenaiLocator locator : locators) {
            try {
                EmbeddingModel model = locator.getFirstAvailableEmbeddingModel();
                if (model != null) {
                    logger.info("Default embedding model from GenaiLocator: {}", model.getClass().getSimpleName());
                    return model;
                }
            } catch (Exception e) {
                logger.debug("Locator could not provide an embedding model: {}", e.getMessage());
            }
        }
        return null;
    }
}
