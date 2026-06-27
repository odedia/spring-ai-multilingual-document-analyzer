package com.odedia.analyzer.services;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Operator-supplied context windows per model, e.g.
 *
 * <pre>
 * app:
 *   ai:
 *     modelContext:
 *       "[openai/gpt-oss-120b]": 131072
 *       "[google/gemma-4-31B-it-qat-w4a16-ct]": 262144
 * </pre>
 *
 * These are authoritative — auto-probing a model server for its limit is
 * unreliable (some reject an oversized request with the limit, others silently
 * ignore it, and proxies may block large probes), so a configured value wins.
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class ModelContextProperties {

    private Map<String, Integer> modelContext = new HashMap<>();

    public Map<String, Integer> getModelContext() {
        return modelContext;
    }

    public void setModelContext(Map<String, Integer> modelContext) {
        this.modelContext = modelContext;
    }
}
