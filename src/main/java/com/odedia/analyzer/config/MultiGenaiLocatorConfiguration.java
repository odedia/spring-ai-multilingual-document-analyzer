package com.odedia.analyzer.config;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.pivotal.cfenv.boot.genai.DefaultGenaiLocator;
import io.pivotal.cfenv.boot.genai.GenaiLocator;
import io.pivotal.cfenv.core.CfEnv;
import io.pivotal.cfenv.core.CfService;

/**
 * Registers one {@link GenaiLocator} per bound GenAI service.
 *
 * The default {@code java-cfenv-boot-tanzu-genai} processor only creates a
 * single {@link GenaiLocator} from the first service it sees, so when an app
 * binds both a chat service and an embedding service (e.g. {@code gpt-oss-1025}
 * and {@code nomic-embed-v2}) only one of them is wired up. This config walks
 * VCAP_SERVICES directly and produces a locator per {@code genai}-tagged
 * service that has an {@code endpoint} credentials block.
 *
 * Pattern adapted from cpage-pivotal/cf-mcp-client.
 *
 * Outside Cloud Foundry the {@link CfEnv#findAllServices()} call returns an
 * empty list, so the produced bean is just an empty list and nothing
 * downstream activates.
 */
@Configuration
public class MultiGenaiLocatorConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(MultiGenaiLocatorConfiguration.class);

    @Bean
    public List<GenaiLocator> manualGenaiLocators(RestClient.Builder builder) {
        CfEnv cfEnv = new CfEnv();

        List<CfService> all = cfEnv.findAllServices();
        logger.info("MultiGenaiLocatorConfiguration: scanning {} bound CF service(s)", all.size());
        for (CfService s : all) {
            logger.info("  service '{}' label='{}' tags={} credentialsKeys={}",
                    s.getName(), s.getLabel(), s.getTags(),
                    s.getCredentials() != null ? s.getCredentials().getMap().keySet() : "(none)");
        }

        List<GenaiLocator> locators = all.stream()
                .filter(this::isGenaiService)
                .map(service -> {
                    logger.info("Registering GenaiLocator for bound service: {}", service.getName());
                    return createGenaiLocator(service, builder);
                })
                .collect(Collectors.toList());

        if (locators.isEmpty()) {
            logger.warn("No GenAI-tagged services with endpoint credentials found in VCAP_SERVICES.");
        } else {
            logger.info("Registered {} GenaiLocator(s) from VCAP_SERVICES.", locators.size());
        }
        return locators;
    }

    private boolean isGenaiService(CfService service) {
        boolean hasGenaiTag = service.existsByTagIgnoreCase("genai")
                || service.existsByLabelStartsWith("genai")
                || service.existsByLabelStartsWith("ai-models");
        boolean hasEndpoint = service.getCredentials() != null
                && service.getCredentials().getMap() != null
                && service.getCredentials().getMap().containsKey("endpoint");
        if (!hasGenaiTag) {
            logger.debug("Skipping service '{}': no genai/ai-models tag or label", service.getName());
        } else if (!hasEndpoint) {
            logger.warn("Service '{}' has genai tag/label but no 'endpoint' in credentials — skipping", service.getName());
        }
        return hasGenaiTag && hasEndpoint;
    }

    private GenaiLocator createGenaiLocator(CfService service, RestClient.Builder builder) {
        Map<String, Object> credentials = service.getCredentials().getMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> endpoint = (Map<String, Object>) credentials.get("endpoint");

        String configUrl = (String) endpoint.get("config_url");
        String apiKey = (String) endpoint.get("api_key");
        String apiBase = (String) endpoint.get("api_base");

        return new DefaultGenaiLocator(builder, configUrl, apiKey, apiBase);
    }
}
