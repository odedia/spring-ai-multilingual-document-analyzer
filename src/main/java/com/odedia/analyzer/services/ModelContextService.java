package com.odedia.analyzer.services;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import io.pivotal.cfenv.core.CfEnv;
import io.pivotal.cfenv.core.CfService;

/**
 * Discovers the maximum context window (in tokens) of each bound GenAI chat
 * model.
 *
 * Tanzu's GenAI config endpoint ({@code /config/v1/endpoint}) advertises model
 * names and capabilities but NOT the context length, and the OpenAI-compatible
 * {@code /v1/models} listing is bare. The reliable way to discover the limit is
 * to ask the model server itself: an over-sized {@code max_tokens} request makes
 * the (vLLM-backed) server reject it with a message that names its own limit,
 * e.g. {@code max_model_len=max_total_tokens=262144}. We parse that number.
 *
 * Results are cached per model. When discovery is impossible (e.g. running
 * locally against Ollama, or the probe fails) we fall back to a conservative
 * configured default so prompt budgeting never blows past a small model.
 */
@Service
public class ModelContextService {

    private static final Logger logger = LoggerFactory.getLogger(ModelContextService.class);
    // Handles "max_model_len=max_total_tokens=262144", "max_model_len: 131072",
    // and "maximum context length is 262144 tokens" — grab the number after the label.
    private static final Pattern MAX_LEN = Pattern.compile(
            "(?:max_model_len|max_total_tokens|maximum context length)\\D*?(\\d{3,})");

    private record Creds(String openAiBase, String apiKey) {
    }

    private final RestClient.Builder restClientBuilder;
    private final int defaultMaxContextTokens;
    private final ModelContextProperties properties;
    private final Map<String, Creds> credsByModel = new ConcurrentHashMap<>();
    private final Map<String, Integer> contextByModel = new ConcurrentHashMap<>();
    private final java.util.Set<String> probedModels = ConcurrentHashMap.newKeySet();

    /** Where a model's window came from: operator config, a successful probe, or the fallback default. */
    public record ContextInfo(int tokens, String source) {
    }

    /** Tokens + whether the value is trustworthy (configured/probed) or just the default. */
    public ContextInfo describe(String modelName) {
        if (modelName != null) {
            Integer configured = properties.getModelContext().get(modelName);
            if (configured != null && configured > 0) {
                return new ContextInfo(configured, "configured");
            }
        }
        int tokens = maxContextTokens(modelName);
        String source = (modelName != null && probedModels.contains(modelName)) ? "probed" : "default";
        return new ContextInfo(tokens, source);
    }

    public ModelContextService(
            RestClient.Builder restClientBuilder,
            ModelContextProperties properties,
            @Value("${app.ai.defaultMaxContextTokens:8192}") int defaultMaxContextTokens) {
        this.restClientBuilder = restClientBuilder;
        this.properties = properties;
        this.defaultMaxContextTokens = defaultMaxContextTokens;
        discoverBoundEndpoints();
    }

    /**
     * Maximum context window (tokens) for the given model. Resolution order:
     * 1. Operator-configured {@code app.ai.modelContext} map (authoritative).
     * 2. Best-effort auto-probe of the model server (cached).
     * 3. Conservative configured default.
     */
    public int maxContextTokens(String modelName) {
        if (modelName == null) {
            return defaultMaxContextTokens;
        }
        Integer configured = properties.getModelContext().get(modelName);
        if (configured != null && configured > 0) {
            return configured;
        }
        return contextByModel.computeIfAbsent(modelName, this::discover);
    }

    /** The conservative default used when a model's window can't be discovered. */
    public int defaultMaxContextTokens() {
        return defaultMaxContextTokens;
    }

    /** Map each bound GenAI model name to the credentials needed to probe it. */
    private void discoverBoundEndpoints() {
        try {
            CfEnv cfEnv = new CfEnv();
            for (CfService service : cfEnv.findAllServices()) {
                if (!isGenai(service)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> endpoint = (Map<String, Object>) service.getCredentials().getMap().get("endpoint");
                if (endpoint == null) {
                    continue;
                }
                String apiBase = (String) endpoint.get("api_base");
                String apiKey = (String) endpoint.get("api_key");
                String configUrl = (String) endpoint.get("config_url");
                if (apiBase == null || apiKey == null) {
                    continue;
                }
                String openAiBase = apiBase.endsWith("/openai") ? apiBase : apiBase + "/openai";
                for (String model : advertisedModels(configUrl, apiKey)) {
                    credsByModel.put(model, new Creds(openAiBase, apiKey));
                }
            }
            logger.info("ModelContextService: probe credentials available for {} model(s)", credsByModel.size());
        } catch (Exception e) {
            logger.info("ModelContextService: no CF GenAI bindings to probe ({}). Using default context {}.",
                    e.getMessage(), defaultMaxContextTokens);
        }
    }

    private boolean isGenai(CfService service) {
        return (service.existsByTagIgnoreCase("genai")
                || service.existsByLabelStartsWith("genai")
                || service.existsByLabelStartsWith("ai-models"))
                && service.getCredentials() != null
                && service.getCredentials().getMap() != null
                && service.getCredentials().getMap().containsKey("endpoint");
    }

    @SuppressWarnings("unchecked")
    private List<String> advertisedModels(String configUrl, String apiKey) {
        if (configUrl == null) {
            return List.of();
        }
        try {
            Map<String, Object> body = restClientBuilder.build().get()
                    .uri(configUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(Map.class);
            List<Map<String, Object>> models = body == null ? null
                    : (List<Map<String, Object>>) body.get("advertisedModels");
            if (models == null) {
                return List.of();
            }
            return models.stream().map(m -> (String) m.get("name")).filter(n -> n != null).toList();
        } catch (Exception e) {
            logger.warn("Could not read advertised models from {}: {}", configUrl, e.getMessage());
            return List.of();
        }
    }

    /**
     * Probe the model server for its context limit by deliberately requesting an
     * impossible number of output tokens and parsing the limit out of the error.
     */
    private int discover(String modelName) {
        Creds creds = credsByModel.get(modelName);
        if (creds == null) {
            logger.info("No probe credentials for model '{}'; using default context {}", modelName,
                    defaultMaxContextTokens);
            return defaultMaxContextTokens;
        }
        String payload = "{\"model\":\"" + modelName
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":2000000000}";
        String responseBody;
        try {
            // Success would be unexpected (the request is intentionally invalid); capture
            // the body either way via exchange so 4xx doesn't throw before we read it.
            responseBody = restClientBuilder.build().post()
                    .uri(creds.openAiBase() + "/v1/chat/completions")
                    .header("Authorization", "Bearer " + creds.apiKey())
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .exchange((req, res) -> new String(res.getBody().readAllBytes()), false);
        } catch (Exception e) {
            responseBody = e.getMessage();
        }
        Integer parsed = parseMaxLen(responseBody);
        if (parsed != null) {
            logger.info("Discovered context window for '{}': {} tokens", modelName, parsed);
            return parsed;
        }
        logger.warn("Could not discover context window for '{}'; using default {}. Probe said: {}",
                modelName, defaultMaxContextTokens, abbreviate(responseBody));
        return defaultMaxContextTokens;
    }

    static Integer parseMaxLen(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = MAX_LEN.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "(null)";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
