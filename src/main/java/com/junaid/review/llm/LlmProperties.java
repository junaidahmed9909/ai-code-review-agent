package com.junaid.review.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM settings under the {@code llm} prefix. Leave {@code apiKey} empty to run rule checks only.
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String apiKey,
        String model,
        String baseUrl,
        int maxTokens,
        Duration timeout) {

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
