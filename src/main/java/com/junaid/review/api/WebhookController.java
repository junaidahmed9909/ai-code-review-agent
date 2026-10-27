package com.junaid.review.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.junaid.review.core.WebhookSignature;
import com.junaid.review.github.GitHubProperties;
import com.junaid.review.service.PullRequestReviewer;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives GitHub webhooks. Every request must carry a valid HMAC signature; anything else is rejected
 * before the body is even parsed.
 */
@RestController
public class WebhookController {

    private static final Set<String> REVIEW_ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");

    private final GitHubProperties props;
    private final PullRequestReviewer reviewer;
    private final ObjectMapper json;

    public WebhookController(GitHubProperties props, PullRequestReviewer reviewer, ObjectMapper json) {
        this.props = props;
        this.reviewer = reviewer;
        this.json = json;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] body) throws IOException {

        if (props.webhookSecret() == null || props.webhookSecret().isBlank()) {
            return reply(HttpStatus.SERVICE_UNAVAILABLE, "Webhook secret is not configured on the server");
        }
        if (!WebhookSignature.isValid(props.webhookSecret(), body, signature)) {
            return reply(HttpStatus.UNAUTHORIZED, "Invalid or missing signature");
        }
        if ("ping".equals(event)) {
            return reply(HttpStatus.OK, "pong");
        }
        if (!"pull_request".equals(event)) {
            return reply(HttpStatus.OK, "Ignored event " + event);
        }

        JsonNode payload = json.readTree(body);
        String action = payload.path("action").asText();
        JsonNode pr = payload.path("pull_request");
        if (!REVIEW_ACTIONS.contains(action)) {
            return reply(HttpStatus.OK, "Ignored action " + action);
        }
        if (pr.path("draft").asBoolean(false)) {
            return reply(HttpStatus.OK, "Skipped draft pull request");
        }
        String owner = payload.path("repository").path("owner").path("login").asText();
        String repo = payload.path("repository").path("name").asText();
        int number = payload.path("number").asInt(pr.path("number").asInt(0));
        String headSha = pr.path("head").path("sha").asText("");
        if (owner.isBlank() || repo.isBlank() || number < 1) {
            return reply(HttpStatus.BAD_REQUEST, "Payload is missing repository or pull request details");
        }

        reviewer.reviewPullRequest(owner, repo, number, headSha);
        return reply(HttpStatus.ACCEPTED, "Review started for " + owner + "/" + repo + "#" + number);
    }

    private static ResponseEntity<Map<String, Object>> reply(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
