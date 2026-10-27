package com.junaid.review.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the Claude Messages API (POST /v1/messages) over plain HTTP with Spring's RestClient.
 * No SDK needed, which keeps the request and response easy to see and test.
 */
public class AnthropicLlmClient implements LlmClient {

    static final String API_VERSION = "2023-06-01";

    private final RestClient http;
    private final LlmProperties props;

    public AnthropicLlmClient(RestClient.Builder builder, LlmProperties props) {
        this.props = props;
        this.http = builder
                .baseUrl(props.baseUrl())
                .defaultHeader("x-api-key", props.apiKey())
                .defaultHeader("anthropic-version", API_VERSION)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String complete(String system, String user) {
        Map<String, Object> body = Map.of(
                "model", props.model(),
                "max_tokens", props.maxTokens(),
                "system", system,
                "messages", List.of(Map.of("role", "user", "content", user)));
        JsonNode response;
        try {
            response = http.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new LlmException("The LLM request failed: " + e.getMessage(), e);
        }
        if (response == null || !response.has("content")) {
            throw new LlmException("The LLM returned an empty response");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.get("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        if ("refusal".equals(response.path("stop_reason").asText())) {
            throw new LlmException("The model declined to answer this request");
        }
        return text.toString().trim();
    }
}
