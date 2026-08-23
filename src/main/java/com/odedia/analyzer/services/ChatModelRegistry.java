package com.odedia.analyzer.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.micrometer.observation.ObservationRegistry;
import io.pivotal.cfenv.boot.genai.GenaiLocator;

/**
 * Discovers and exposes the chat models available to this application.
 *
 * Sources, in priority order:
 * 1. Tanzu Platform 10.4 GenAI multi-binding via {@link GenaiLocator} beans —
 *    every advertised CHAT-capable model on every bound service becomes
 *    selectable.
 * 2. The auto-configured {@link ChatModel} (single model from Spring AI
 *    properties — local Ollama, single OpenAI key, etc.).
 *
 * Two ChatClients are kept per model: a plain one (all tool schemas sent) and a
 * Tool-Search one (Lucene index, session id = conversation id). Auto-config
 * cannot wrap these because they are built manually from GenAI locators.
 */
@Service
public class ChatModelRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChatModelRegistry.class);

    private final Map<String, ChatClient> clientsByModel = new LinkedHashMap<>();
    private final Map<String, ChatModel> modelsByName = new LinkedHashMap<>();
    private String defaultModelName;

    public ChatModelRegistry(
            ObjectProvider<List<GenaiLocator>> locatorsProvider,
            ObjectProvider<ChatModel> chatModelProvider,
            @Value("${spring.ai.ollama.chat.model:${spring.ai.ollama.chat.options.model:}}") String ollamaModel,
            @Value("${spring.ai.openai.chat.model:${spring.ai.openai.chat.options.model:}}") String openAiModel) {

        List<GenaiLocator> locators = locatorsProvider.getIfAvailable();
        if (locators != null && !locators.isEmpty()) {
            for (int i = 0; i < locators.size(); i++) {
                GenaiLocator locator = locators.get(i);
                List<String> chatModels = safeList(() -> locator.getModelNamesByCapability("CHAT"));
                List<String> toolModels = safeList(() -> locator.getModelNamesByCapability("TOOLS"));

                List<String> combined = new ArrayList<>();
                combined.addAll(chatModels);
                for (String name : toolModels) {
                    if (!combined.contains(name)) {
                        combined.add(name);
                    }
                }

                for (String name : combined) {
                    if (clientsByModel.containsKey(name)) {
                        continue;
                    }
                    try {
                        register(name, locator.getChatModelByName(name));
                        logger.info("Registered GenAI chat model '{}' from locator[{}]", name, i);
                    } catch (Exception e) {
                        logger.warn("Failed to register GenAI chat model {} from locator[{}]: {}",
                                name, i, e.getMessage());
                    }
                }
            }

            if (!clientsByModel.isEmpty()) {
                defaultModelName = clientsByModel.keySet().iterator().next();
            }
        }

        if (clientsByModel.isEmpty()) {
            ChatModel autoConfigured = chatModelProvider.getIfAvailable();
            if (autoConfigured != null) {
                String fallbackName = firstNonBlank(ollamaModel, openAiModel, "default");
                register(fallbackName, autoConfigured);
                defaultModelName = fallbackName;
                logger.info("Registered auto-configured chat model as '{}'", fallbackName);
            } else {
                logger.warn("No chat model available — neither GenaiLocator nor auto-configured ChatModel was found.");
            }
        }
    }

    private void register(String name, ChatModel model) {
        modelsByName.put(name, model);
        clientsByModel.put(name, ChatClient.builder(model).build());
    }

    public List<String> listModels() {
        return Collections.unmodifiableList(new ArrayList<>(clientsByModel.keySet()));
    }

    public String getDefaultModelName() {
        return defaultModelName;
    }

    /**
     * Returns the ChatClient for the requested model, falling back to the
     * default when the requested name is null, blank, or unknown.
     */
    public ChatClient clientFor(String modelName) {
        return clientFor(modelName, false);
    }

    public ChatClient clientFor(String modelName, boolean toolSearch) {
        return clientFor(modelName, toolSearch, null);
    }

    /**
     * When {@code toolSearch} is true, schemas sent to the model are filtered through
     * Tool Search. {@code toolCallingManager} must resolve the full document-tool
     * catalog so a model that calls {@code listDocuments} (named in the system prompt)
     * still executes instead of throwing "No ToolCallback found".
     */
    public ChatClient clientFor(String modelName, boolean toolSearch, ToolCallingManager toolCallingManager) {
        String resolved = resolve(modelName).orElse(defaultModelName);
        if (toolSearch && resolved != null && toolCallingManager != null) {
            ChatModel model = modelsByName.get(resolved);
            if (model != null) {
                try {
                    return ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                            ToolSearchToolCallingAdvisor.builder()
                                    .toolIndex(new LuceneToolIndex())
                                    .sessionIdKeyName(ChatMemory.CONVERSATION_ID)
                                    .toolCallingManager(toolCallingManager))
                            .build();
                } catch (Exception e) {
                    logger.warn("Tool-search ChatClient for '{}' failed; sending all tool schemas: {}",
                            resolved, e.getMessage());
                }
            }
        }
        if (resolved == null) {
            return defaultClient();
        }
        ChatClient client = clientsByModel.get(resolved);
        if (client == null) {
            logger.warn("Requested chat model '{}' not registered; falling back to default '{}'",
                    modelName, defaultModelName);
            return defaultClient();
        }
        return client;
    }

    public ChatClient defaultClient() {
        if (defaultModelName == null) {
            throw new IllegalStateException("No chat model is configured");
        }
        return clientsByModel.get(defaultModelName);
    }

    private static List<String> safeList(java.util.function.Supplier<List<String>> supplier) {
        try {
            List<String> result = supplier.get();
            return result != null ? result : Collections.emptyList();
        } catch (Exception e) {
            logger.debug("Capability lookup failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    public Optional<String> resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return Optional.ofNullable(defaultModelName);
        }
        return clientsByModel.containsKey(requested)
                ? Optional.of(requested)
                : Optional.ofNullable(defaultModelName);
    }
}
