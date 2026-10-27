package com.junaid.review.core;

import java.util.Objects;

/**
 * One review comment.
 *
 * @param source RULE for the built-in checks, AI for model-generated comments
 */
public record Finding(String ruleId, Severity severity, String path, int line, String message, String suggestion, Source source) {

    public enum Severity { HIGH, MEDIUM, LOW }

    public enum Source { RULE, AI }

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId is required");
        Objects.requireNonNull(severity, "severity is required");
        Objects.requireNonNull(path, "path is required");
        Objects.requireNonNull(message, "message is required");
        Objects.requireNonNull(source, "source is required");
        if (line < 1) {
            throw new IllegalArgumentException("line must be 1 or more");
        }
        suggestion = suggestion == null ? "" : suggestion;
    }

    /** Markdown body for the GitHub comment. */
    public String toCommentBody() {
        String label = switch (severity) {
            case HIGH -> "🔴 **High**";
            case MEDIUM -> "🟠 **Medium**";
            case LOW -> "🟡 **Low**";
        };
        StringBuilder sb = new StringBuilder(label).append(" · `").append(ruleId).append("`\n\n").append(message);
        if (!suggestion.isBlank()) {
            sb.append("\n\n**Suggestion:** ").append(suggestion);
        }
        if (source == Source.AI) {
            sb.append("\n\n<sub>AI-generated. Please double-check before acting on it.</sub>");
        }
        return sb.toString();
    }
}
