package com.junaid.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.junaid.review.config.ReviewProperties;
import com.junaid.review.github.GitHubClient;
import com.junaid.review.github.GitHubProperties;
import com.junaid.review.llm.AnthropicLlmClient;
import com.junaid.review.llm.LlmClient;
import com.junaid.review.llm.LlmProperties;
import com.junaid.review.service.ReviewEngine;
import com.junaid.review.service.ReviewStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.web.client.RestClient;

/**
 * Runs one review without a server, for use inside GitHub Actions (see .github/workflows/review.yml).
 * Same engine and GitHub client as the webhook app; no hosting or webhook needed.
 *
 * Environment: GITHUB_REPOSITORY (owner/name), PR_NUMBER, HEAD_SHA, GITHUB_TOKEN,
 * optional ANTHROPIC_API_KEY and LLM_MODEL.
 */
public final class ReviewCli {

    private ReviewCli() {}

    public static void main(String[] args) {
        String[] repo = require("GITHUB_REPOSITORY").split("/", 2);
        int number = Integer.parseInt(require("PR_NUMBER"));
        String headSha = System.getenv().getOrDefault("HEAD_SHA", "");

        GitHubClient github = new GitHubClient(RestClient.builder(),
                new GitHubProperties(require("GITHUB_TOKEN"), "", "https://api.github.com", true));

        String apiKey = System.getenv().getOrDefault("ANTHROPIC_API_KEY", "");
        String model = System.getenv().getOrDefault("LLM_MODEL", "claude-sonnet-5-5");
        LlmProperties llmProps = new LlmProperties(apiKey, model.isBlank() ? "claude-sonnet-5-5" : model,
                "https://api.anthropic.com", 2048, Duration.ofSeconds(90));
        LlmClient llm = llmProps.hasKey() ? new AnthropicLlmClient(RestClient.builder(), llmProps) : LlmClient.DISABLED;

        ReviewEngine engine = new ReviewEngine(llm, new ReviewProperties(20, 8, 12_000), new ObjectMapper(),
                new ReviewStats(new SimpleMeterRegistry()));

        String diff = github.fetchDiff(repo[0], repo[1], number);
        if (diff.isBlank()) {
            System.out.println("Empty diff; nothing to review.");
            return;
        }
        ReviewEngine.ReviewResult result = engine.review(diff);
        github.postReview(repo[0], repo[1], number, headSha, result.summaryMarkdown(), result.comments());
        System.out.printf("Reviewed %d file(s) in %d ms: %d comments posted (AI pass %s).%n",
                result.filesReviewed(), result.durationMs(), result.comments().size(), result.aiUsed() ? "on" : "off");
        result.warnings().forEach(w -> System.out.println("Warning: " + w));
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return v;
    }
}
