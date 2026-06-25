package com.odedia.analyzer.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.pivotal.cfenv.boot.genai.GenaiLocator;

/**
 * Logs the chat / embedding / tool models advertised by every registered
 * {@link GenaiLocator}. Helps confirm what a Tanzu GenAI multi-binding
 * actually exposes when the platform-side config returns a 502 or the wrong
 * model gets selected.
 */
@Component
public class GenaiStartupReporter {

    private static final Logger logger = LoggerFactory.getLogger(GenaiStartupReporter.class);

    private final List<GenaiLocator> locators;

    public GenaiStartupReporter(List<GenaiLocator> locators) {
        this.locators = locators;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        if (locators == null || locators.isEmpty()) {
            logger.info("GenaiStartupReporter: no GenaiLocator beans present.");
            return;
        }

        for (int i = 0; i < locators.size(); i++) {
            GenaiLocator locator = locators.get(i);
            logger.info("--- GenaiLocator[{}] {} ---", i, locator.getClass().getSimpleName());
            logCapability(locator, "CHAT");
            logCapability(locator, "EMBEDDING");
            logCapability(locator, "TOOLS");
            try {
                logger.info("  all model names: {}", locator.getModelNames());
            } catch (Exception e) {
                logger.warn("  all model names lookup failed: {}", e.getMessage());
            }
        }
    }

    private void logCapability(GenaiLocator locator, String capability) {
        try {
            List<String> names = locator.getModelNamesByCapability(capability);
            logger.info("  {} models: {}", capability, names);
        } catch (Exception e) {
            logger.warn("  {} lookup failed: {}", capability, e.getMessage());
        }
    }
}
