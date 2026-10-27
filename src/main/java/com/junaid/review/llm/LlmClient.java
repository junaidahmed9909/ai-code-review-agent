package com.junaid.review.llm;

/** The one thing the app needs from a language model. Kept tiny so providers are easy to swap or fake in tests. */
public interface LlmClient {

    boolean isAvailable();

    /** Sends one system prompt and one user message; returns the model's text reply. */
    String complete(String system, String user);

    /** Used when no API key is configured. */
    LlmClient DISABLED = new LlmClient() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String complete(String system, String user) {
            throw new LlmException("No LLM API key is configured");
        }
    };
}
